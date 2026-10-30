package pl.wsztajerowski.baas.jobs;

import pl.wsztajerowski.baas.image.RunnerImage;
import pl.wsztajerowski.baas.model.BenchmarkType;
import pl.wsztajerowski.baas.model.JobItem;
import pl.wsztajerowski.baas.model.JobLayout;
import pl.wsztajerowski.baas.model.JobStatus;
import pl.wsztajerowski.baas.model.RunnerArgs;
import pl.wsztajerowski.baas.model.TagKeys;
import pl.wsztajerowski.baas.model.dynamodb.JobItemConditions;
import pl.wsztajerowski.baas.model.dynamodb.JobItemMapper;
import pl.wsztajerowski.baas.model.dynamodb.MeasurementItemMapper;
import pl.wsztajerowski.baas.model.ResultKeys;
import pl.wsztajerowski.baas.session.DeploymentNames;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class UserDataScriptBuilder {

    /** Bump when a field is added or renamed, so `baas jobs diff` can tell structure from content. */
    public static final int MANIFEST_SCHEMA_VERSION = 7;

    /**
     * EC2 refuses user-data over 16 KB raw, and the launch then fails outright. The comments below
     * document the script for whoever reads this file, not for the instance, so comment-only lines
     * are dropped before the script is rendered — they were most of its size. Nothing in the body
     * may therefore depend on a comment line surviving; the heredoc holds none.
     */
    static final int USER_DATA_LIMIT_BYTES = 16 * 1024;

    // Static script body — variables are prepended by build()
    private static final String SCRIPT_BODY = withoutCommentLines(withContractNames("""
        # Job status lives on the job item (pk = JOB) in the results table, not in S3. Defined
        # first, before anything else runs: the watchdog below is a forked subshell, which sees
        # only the functions defined before the fork, and `job_status timed-out` runs in it. A
        # definition cannot fail, so the watchdog still starts as soon as INSTANCE_ID resolves.
        # The instance writes only its status (and its instance id, if the CLI could not), only to
        # the item the CLI's reservation created — attribute_exists(pk), so a wrong key creates
        # nothing — and never over a terminal status (JOB_STATUS_GUARD, rendered from the CLI's own
        # expression). Every value spliced into the JSON is constrained: the sort key is a fixed
        # timestamp plus the job id, the status comes from a fixed set, the instance id from IMDS.
        # Retries and timeouts are set on this one command and exported nowhere — the runner's Java
        # SDK reads the same variables. Each attempt is bounded at 5 s to connect and 10 s to read;
        # the second argument caps the attempts (default 3). Standard-mode backoff adds up to 2 s
        # after the first attempt and 4 s after the second, so 3 attempts cost at most ~51 s and 2
        # cost ~32 s. A failure is logged and never stops the script: the boot log upload and the
        # termination after it must still run. Returns 0 when the status was recorded, 2 when the
        # condition refused it (the job already has an outcome, or there is no job item at this
        # key), and 1 on any other failure.
        job_status() {
          local status="$1" attempts="${2:-3}" update names values err rc
          if [[ -n "${INSTANCE_ID}" ]]; then
            update='SET #status = :s, #instanceId = if_not_exists(#instanceId, :iid)'
            names='{"#status":"@STATUS@","#instanceId":"@INSTANCE_ID@"}'
            values='{":s":{"S":"'"${status}"'"},":iid":{"S":"'"${INSTANCE_ID}"'"},'"${JOB_STATUS_GUARD_VALUES}"'}'
          else
            update='SET #status = :s'
            names='{"#status":"@STATUS@"}'
            values='{":s":{"S":"'"${status}"'"},'"${JOB_STATUS_GUARD_VALUES}"'}'
          fi
          err=$(AWS_RETRY_MODE=standard AWS_MAX_ATTEMPTS="${attempts}" aws dynamodb update-item \
            --region "${AWS_REGION}" --table-name "${RESULTS_TABLE}" \
            --key '{"@PK@":{"S":"@JOB_PARTITION@"},"@SK@":{"S":"'"${JOB_SORT_KEY}"'"}}' \
            --update-expression "${update}" \
            --condition-expression "@EXISTS@ AND ${JOB_STATUS_GUARD}" \
            --expression-attribute-names "${names}" \
            --expression-attribute-values "${values}" \
            --cli-connect-timeout 5 --cli-read-timeout 10 2>&1 >/dev/null)
          rc=$?
          if [[ $rc -eq 0 ]]; then
            echo "job_status: ${status}"
            return 0
          elif [[ "${err}" == *ConditionalCheckFailedException* ]]; then
            echo "job_status: ${status} not recorded: status already terminal, or no job item at this key"
            return 2
          fi
          echo "job_status: ${status} not recorded (exit ${rc}): ${err}"
          return 1
        }

        # Ships what the job has produced so far to its prefix: the boot log, the benchmark
        # process output (the runner redirects it into WORK_DIR/PROCESS_OUTPUT_FILE as it runs, and
        # uploads it as PROCESS_OUTPUT_NAME) and
        # every *.log below WORK_DIR under logs/ — the keys the runner itself uses, so after a
        # completed job this overwrites rather than duplicates. Defined before the watchdog forks,
        # which calls it, and serialised into the shutdown hook below, so the explicit uploads and
        # the shutdown-time one are the same code. --recursive from a local directory lists
        # nothing in the bucket, which RunnerRole could not. Every upload is best effort.
        ship_logs() {
          local dest="s3://${S3_BUCKET}/${RESULT_PATH}"
          aws s3 cp /var/log/cloud-init-output.log "${dest}/@BOOT_LOG@" || true
          [[ -f "${WORK_DIR}/${PROCESS_OUTPUT_FILE}" ]] \\
            && { aws s3 cp "${WORK_DIR}/${PROCESS_OUTPUT_FILE}" "${dest}/${PROCESS_OUTPUT_NAME}" || true; }
          [[ -d "${WORK_DIR}" ]] && { aws s3 cp "${WORK_DIR}" "${dest}/@LOGS@" --recursive \\
            --exclude '*' --include '*.log' --only-show-errors || true; }
          return 0
        }

        TOKEN=$(curl -sX PUT "http://169.254.169.254/latest/api/token" \\
          -H "X-aws-ec2-metadata-token-ttl-seconds: 300")
        INSTANCE_ID=$(curl -sH "X-aws-ec2-metadata-token: $TOKEN" \\
          http://169.254.169.254/latest/meta-data/instance-id)

        # Layer 1: background watchdog (fires even if Java deadlocks)
        (
          sleep ${WALL_CLOCK_HARD_KILL}
          echo "WATCHDOG: hard-kill cap exceeded; terminating $INSTANCE_ID"
          # Before the log upload, so the outcome is on record even if the upload stalls.
          job_status timed-out
          # This path never reaches the normal upload below, and it is exactly the
          # case a user needs the logs for — ship them before the instance disappears.
          ship_logs
          aws ec2 terminate-instances --instance-ids "$INSTANCE_ID" --region "${AWS_REGION}" || shutdown -h now
        ) &

        # After the watchdog, never before it. Fills in the instance id when the CLI's own
        # `launched` write did not land. Two attempts rather than three: everything before the JVM
        # starts has to fit in the watchdog margin, whose floor is 60 s.
        # A refusal means the job already has an outcome — cancelled by a Ctrl+C or `baas jobs
        # terminate` that could not find this instance while it was still launching — or that
        # there is no job item at this key. Either way nobody wants this benchmark, so it is not
        # run: the instance ships its boot log and terminates. Without this, a cancelled job whose
        # instance the CLI missed would run to its timeout, paid, and hidden from `--in-flight`.
        # Any other failure is not a refusal, and the job goes ahead unrecorded.
        job_status running 2
        if [[ $? -eq 2 ]]; then
          echo "Job already has an outcome, or has no job item: not starting the benchmark."
          ship_logs
          aws ec2 terminate-instances --instance-ids "$INSTANCE_ID" --region "${AWS_REGION}" || shutdown -h now
          exit 0
        fi

        # The shutdown hook: a stop from outside — Ctrl+C on `baas run`, `baas jobs terminate`, the
        # CLI's poll cap, the console — is a TerminateInstances, which by default shuts the OS
        # down gracefully, and systemd then runs ExecStop. Stopped in reverse order of start, it
        # runs while the network (After=network-online.target) and the instance credentials are
        # still up; billing has already stopped at shutting-down. TimeoutStopSec bounds a stalled
        # upload. The hook file is ship_logs itself plus the variables it reads, run by an explicit
        # /bin/bash because a shebang line would be stripped with the comments. A unit that fails
        # to start loses only the external-stop case: every exit of this script still ships.
        ship_logs_hook() {
          printf 'export S3_BUCKET=%q RESULT_PATH=%q WORK_DIR=%q AWS_REGION=%q\\n' \\
            "${S3_BUCKET}" "${RESULT_PATH}" "${WORK_DIR}" "${AWS_REGION}"
          printf 'export PROCESS_OUTPUT_FILE=%q PROCESS_OUTPUT_NAME=%q\\n' \\
            "${PROCESS_OUTPUT_FILE}" "${PROCESS_OUTPUT_NAME}"
          declare -f ship_logs
          echo ship_logs
        }
        ship_logs_hook > /usr/local/sbin/baas-ship-logs
        cat > /etc/systemd/system/baas-ship-logs.service <<'UNIT'
        [Unit]
        Description=Ship the BaaS job logs to S3 before the instance goes away
        Wants=network-online.target
        After=network-online.target
        [Service]
        Type=oneshot
        RemainAfterExit=yes
        ExecStart=/bin/true
        ExecStop=/bin/bash /usr/local/sbin/baas-ship-logs
        TimeoutStopSec=90
        UNIT
        systemctl daemon-reload && systemctl start baas-ship-logs.service \\
          || echo "baas-ship-logs.service not started: a termination from outside will not ship the logs"

        # Nothing is installed here. Corretto, perf, the AWS CLI and async-profiler are baked
        # into the AMI by `baas admin image build` from infra/runner-image.yaml — a runner that
        # installed its own toolchain would measure on a slightly different machine every time.

        mkdir -p /app
        # Run from a real working directory. cloud-init starts us in /, and the runner
        # scans the tree below its cwd for .log files to upload — from / that means
        # walking the whole root filesystem, and dying on /proc entries that vanish
        # mid-walk. The GitHub Actions flow this replaced ran from its workspace dir.
        cd /app

        # ── Environment manifest ──────────────────────────────────────────────────
        # Written and uploaded BEFORE the benchmark, so a job that crashes still leaves a
        # record of what it crashed on — the same reasoning that ships cloud-init-output.log.
        # This is the observation; infra/runner-image.yaml is only the declaration, and this
        # additionally carries what the image cannot control: instance type, CPU model,
        # resolved patch levels.
        # Every value is captured into a variable first, so the manifest body below is nothing but
        # ${VAR} references. Inlining the command substitutions would put quotes, parentheses and
        # awk programs inside a JSON string inside a heredoc — three levels of quoting, and a
        # mistake in any of them produces a file that only fails weeks later in `baas jobs diff`.
        json_escape() { printf '%s' "$1" | sed -e 's/\\\\/\\\\\\\\/g' -e 's/"/\\\\"/g'; }
        lscpu_field() { lscpu | grep -m1 "^$1" | cut -d: -f2- | tr -d ' '; }

        INSTANCE_TYPE=$(curl -sH "X-aws-ec2-metadata-token: $TOKEN" \\
          http://169.254.169.254/latest/meta-data/instance-type)
        IMAGE_VERSION_ACTUAL=$(cat /etc/baas-image-version 2>/dev/null || echo "${IMAGE_VERSION}")
        CPU_MODEL_RAW=$(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2- | sed 's/^ *//')
        CPU_MODEL=$(json_escape "$CPU_MODEL_RAW")
        # uname -m cannot emit a double quote or backslash, so — unlike every other value
        # captured here — this one needs no escaping before it reaches the manifest.
        CPU_ARCH=$(uname -m)
        # Logical CPUs. Recorded as `vcpus`, never `cores`: schema 6 called this count `cores`, which
        # beside threadsPerCore read as twice the hardware threads the box has.
        CPU_VCPUS=$(nproc)
        CPU_THREADS_PER_CORE=$(lscpu_field "Thread")
        # Physical cores, empty unless lscpu reports both factors as numbers: $(( )) on an empty
        # operand prints a bash error and assigns nothing, and nothing here stops on an error.
        CPU_SOCKETS=$(lscpu_field "Socket(s)")
        CPU_CORES_PER_SOCKET=$(lscpu_field "Core(s) per socket")
        CPU_PHYSICAL_CORES=
        [[ $CPU_SOCKETS =~ ^[0-9]+$ && $CPU_CORES_PER_SOCKET =~ ^[0-9]+$ ]] && CPU_PHYSICAL_CORES=$((CPU_SOCKETS * CPU_CORES_PER_SOCKET))
        CPU_MAX_MHZ=$(lscpu_field "CPU max MHz")
        # Whole MiB, rounded down: two instances of one type report MemTotal a few KiB apart, which
        # is no difference for a measurement, and `baas jobs diff` compares what is stored.
        MEMORY_TOTAL_MIB=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)
        SWAP_TOTAL_MIB=$(awk '/SwapTotal/ {print int($2/1024)}' /proc/meminfo)
        OS_VERSION=$(json_escape "$(. /etc/os-release && echo "$PRETTY_NAME")")
        KERNEL_RELEASE=$(uname -r)
        JVM_VERSION_RAW=$(java -version 2>&1 | head -1)
        JVM_VERSION=$(json_escape "$JVM_VERSION_RAW")
        # jdk tag and jvm.release: same observation as JVM_VERSION_RAW above, projected to the
        # version number the banner quotes (e.g. "25.0.4") — not a second `java -version`.
        JDK_VERSION=$(printf '%s' "$JVM_VERSION_RAW" | sed -n 's/.*"\\(.*\\)".*/\\1/p')
        JVM_RELEASE=$(json_escape "$JDK_VERSION")
        # Who built the JVM. The banner above names only the version, so Corretto 25.0.4 and
        # another vendor's 25.0.4 read the same there — and an image extension may install either.
        # One JVM call yields every property; each is then picked out by its exact key.
        JVM_PROPS=$(java -XshowSettings:properties -version 2>&1)
        jvm_prop() { printf '%s\\n' "$JVM_PROPS" | awk -F' = ' -v key="$1" '{ sub(/^ +/, "", $1) } $1 == key { print $2; exit }'; }
        JVM_VENDOR_RAW=$(jvm_prop java.vendor)
        JVM_VENDOR=$(json_escape "$JVM_VENDOR_RAW")
        JVM_VENDOR_VERSION=$(json_escape "$(jvm_prop java.vendor.version)")
        JVM_NAME=$(json_escape "$(jvm_prop java.vm.name)")
        # Capture, then default: `cmd | head -1 || echo absent` never reaches the echo, because a
        # pipeline's status is head's, and head succeeds on empty input. A missing tool then recorded
        # "" — and asprof, through 2>&1, bash's "No such file" as its version. No pipefail: this
        # script's error handling is deliberately explicit (no set -e).
        PERF_VERSION=$(perf --version 2>/dev/null | head -1)
        PERF_VERSION=$(json_escape "${PERF_VERSION:-absent}")
        # Cleared first: when asprof is missing nothing below assigns it, and the default would
        # otherwise take any ASYNC_PROFILER_VERSION already in the environment.
        ASYNC_PROFILER_VERSION=
        [ -x /app/async-profiler/bin/asprof ] && ASYNC_PROFILER_VERSION=$(/app/async-profiler/bin/asprof --version 2>&1 | head -1)
        ASYNC_PROFILER_VERSION=$(json_escape "${ASYNC_PROFILER_VERSION:-absent}")
        PERF_EVENT_PARANOID=$(sysctl -n kernel.perf_event_paranoid 2>/dev/null)
        KPTR_RESTRICT=$(sysctl -n kernel.kptr_restrict 2>/dev/null)
        TRANSPARENT_HUGEPAGES=$(cat /sys/kernel/mm/transparent_hugepage/enabled 2>/dev/null)
        # The environment only. The job's identity is on its job item and tags, never here.

        cat > /app/environment.json <<MANIFEST
        {
          "schemaVersion": ${MANIFEST_SCHEMA_VERSION},
          "machine": {
            "imageVersion": "${IMAGE_VERSION_ACTUAL}",
            "amiId": "${AMI_ID}",
            "instanceType": "${INSTANCE_TYPE}"
          },
          "cpu": {
            "model": "${CPU_MODEL}",
            "arch": "${CPU_ARCH}",
            "vcpus": "${CPU_VCPUS}",
            "physicalCores": "${CPU_PHYSICAL_CORES}",
            "threadsPerCore": "${CPU_THREADS_PER_CORE}",
            "maxMhz": "${CPU_MAX_MHZ}"
          },
          "memory": {
            "totalMib": "${MEMORY_TOTAL_MIB}",
            "swapTotalMib": "${SWAP_TOTAL_MIB}"
          },
          "os": {
            "version": "${OS_VERSION}",
            "kernelRelease": "${KERNEL_RELEASE}"
          },
          "jvm": {
            "version": "${JVM_VERSION}",
            "release": "${JVM_RELEASE}",
            "vendor": "${JVM_VENDOR}",
            "vendorVersion": "${JVM_VENDOR_VERSION}",
            "name": "${JVM_NAME}"
          },
          "tools": {
            "perf": "${PERF_VERSION}",
            "asyncProfiler": "${ASYNC_PROFILER_VERSION}"
          },
          "tunables": {
            "perfEventParanoid": "${PERF_EVENT_PARANOID}",
            "kptrRestrict": "${KPTR_RESTRICT}",
            "transparentHugepages": "${TRANSPARENT_HUGEPAGES}"
          }
        }
        MANIFEST

        # Several hundred lines, kept out of environment.json so its ~20 high-signal fields
        # stay readable.
        rpm -qa | sort > /app/packages.txt

        aws s3 cp /app/environment.json "s3://${S3_BUCKET}/${RESULT_PATH}/@ENVIRONMENT@"
        aws s3 cp /app/packages.txt "s3://${S3_BUCKET}/${RESULT_PATH}/@PACKAGES@"

        # The runner JAR comes from the bucket and nowhere else. It used to be resolved at boot
        # from an unpinned upstream "newest release" pointer, so two jobs a week apart could
        # execute different runner code under a tool whose entire product is comparability — the
        # same class of drift as the boot-time package upgrade that finding A8 removed from this
        # script. The CLI now pins it to its own version and seeds it checksum-verified, which is
        # also why this instance reaches no host outside the account.
        aws s3 cp "s3://${S3_BUCKET}/${RUNNER_JAR_S3_KEY}" /app/benchmark-runner.jar

        aws s3 cp "s3://${S3_BUCKET}/${BENCHMARK_JAR_S3_KEY}" /app/benchmark-under-test.jar

        # Layer 2: benchmark process with its own timeout
        # --results-table: the table name is not a secret — unlike the Mongo connection string it
        # replaced, it carries no credentials, so it travels in user-data instead of being fetched
        # from SSM at boot. Access is granted by RunnerRole, not by knowing the name. Every job
        # names the table: `baas run` resolves it before provisioning and fails when it cannot, and
        # job status lives there too, so there is no job without one.
        # BENCHMARK_PARAMS_ARRAY and RUNNER_TAGS_ARRAY are array literals written by build(),
        # one quoted element per argument, so bash parses them once, as data — no eval. The
        # benchmark's arguments follow the runner's --, so none of them can be read as a runner
        # option: they reach the benchmark JAR verbatim.
        # RunCommand.buildRunnerTags already rejects a caller tag whose key is
        # machine-observed (imageVersion, instanceType, jdk, jvmVendor, cpuModel, cpuArch, type),
        # so the caller tags should never actually collide with the six observed --tag lines
        # below.
        # The six --tag lines below reach the item's tags map, so `baas results query` can filter
        # and group by the environment without fetching anything from S3. They are the values
        # OBSERVED above, not the ones the CLI passed down, so a result's tags cannot
        # disagree with its own environment.json. They are listed AFTER the caller-tags
        # expansion above, not before, as defence in depth: the runner parses --tag into
        # a picocli Map option, which is LAST-WINS on a duplicate key, so this order keeps
        # the observed value in charge even if a reserved key ever slips past the
        # CLI-side guard above.
        timeout "${BENCHMARK_TIMEOUT}" java -jar /app/benchmark-runner.jar "${BENCHMARK_TYPE}" \\
          @JOB_ID_FLAG@     "${JOB_ID}" \\
          @CREATED_AT_FLAG@     "${CREATED_AT}" \\
          @RESULT_PATH_FLAG@    "${RESULT_PATH}" \\
          @S3_BUCKET_FLAG@      "${S3_BUCKET}" \\
          @BENCHMARK_PATH_FLAG@ /app/benchmark-under-test.jar \\
          @RESULTS_TABLE_FLAG@  "${RESULTS_TABLE}" \\
          "${RUNNER_TAGS_ARRAY[@]}" \\
          @TAG_FLAG@ "@IMAGE_VERSION_TAG@=${IMAGE_VERSION_ACTUAL}" \\
          @TAG_FLAG@ "@INSTANCE_TYPE_TAG@=${INSTANCE_TYPE}" \\
          @TAG_FLAG@ "@JDK_TAG@=${JDK_VERSION}" \\
          @TAG_FLAG@ "@JVM_VENDOR_TAG@=${JVM_VENDOR_RAW}" \\
          @TAG_FLAG@ "@CPU_MODEL_TAG@=${CPU_MODEL_RAW}" \\
          @TAG_FLAG@ "@CPU_ARCH_TAG@=${CPU_ARCH}" \\
          -- "${BENCHMARK_PARAMS_ARRAY[@]}"
        EXIT_CODE=$?

        # The outcome, on the job item — what `baas run` polls and `baas jobs list` shows.
        STATUS="completed"; [[ $EXIT_CODE -ne 0 ]] && STATUS="failed:${EXIT_CODE}"
        job_status "${STATUS}"

        # Ship the logs before self-terminating — the instance is about to disappear and this is
        # the only record of what went wrong on a failed job. A benchmark killed by its timeout
        # took the runner, and the runner's own uploads, with it; this still has its output.
        ship_logs

        # Self-termination, with the watchdog left armed: if this request fails, the watchdog
        # is what remains. The instance is launched with shutdown behaviour TERMINATE, so an OS
        # shutdown terminates it without an API call, a permission or an instance id.
        aws ec2 terminate-instances --instance-ids "$INSTANCE_ID" --region "${AWS_REGION}" || shutdown -h now
        """));

    /**
     * The script for one job, from the objects the launch holds (review K5): sixteen positional
     * values, eleven of them strings, made a swapped pair compile and fail only on a paid instance.
     * The benchmark JAR's key is derived here exactly as the launch uploads it.
     */
    public String build(String region, DeploymentNames deployment, JobItem job, RunnerImage image,
                        JobLaunch.Request request, String runnerJarS3Key) {
        return render(region, deployment.bucket(), request.benchmarkType(),
            job.jobId(), job.resultPath(), job.createdAt().toString(),
            JobLayout.benchmarkJarKey(job.project(), job.jobId()),
            request.timeoutSeconds(), request.watchdogSeconds(),
            image.imageVersion(), image.amiId(), runnerJarS3Key,
            deployment.resultsTable(), job.sortKey(), request.benchmarkParams(), request.runnerTags());
    }

    private String render(String region, String bucket, String benchmarkType,
                          String jobId, String resultPath, String createdAt,
                          String benchmarkJarS3Key, int benchmarkTimeoutSeconds,
                          int wallClockHardKillSeconds, String imageVersion, String amiId,
                          String runnerJarS3Key, String resultsTableName, String jobSortKey,
                          List<String> benchmarkParams, Map<String, String> runnerTags) {
        List<String> tagArgs = runnerTags.entrySet().stream()
            .flatMap(e -> Stream.of(RunnerArgs.TAG, e.getKey() + "=" + e.getValue()))
            .toList();

        String script = "#!/bin/bash\n" +
            "# No set -e — errors handled explicitly so watchdog always starts\n" +
            export("AWS_REGION", region) +
            export("S3_BUCKET", bucket) +
            export("BENCHMARK_TYPE", benchmarkType) +
            export("JOB_ID", jobId) +
            export("RESULT_PATH", resultPath) +
            // Where the job's files are written and what ship_logs uploads from. The script still
            // writes `/app` literally where it runs the job; this only lets tests point the upload
            // at a directory of their own.
            export("WORK_DIR", "/app") +
            // The benchmark process's output: the file the runner writes it to as it runs, and the
            // name it uploads it as. ship_logs uploads the first as the second, so a job stopped
            // early leaves it at the same key a completed one does.
            export("PROCESS_OUTPUT_FILE", BenchmarkType.fromCommandName(benchmarkType).workingOutputName()) +
            export("PROCESS_OUTPUT_NAME", BenchmarkType.fromCommandName(benchmarkType).processOutputName()) +
            // One clock read per job: this instant named the job's prefix and is what the runner
            // stores as createdAt, so the two cannot disagree. The instance's own clock is not
            // consulted.
            export("CREATED_AT", createdAt) +
            export("BENCHMARK_JAR_S3_KEY", benchmarkJarS3Key) +
            export("BENCHMARK_TIMEOUT", benchmarkTimeoutSeconds) +
            export("WALL_CLOCK_HARD_KILL", wallClockHardKillSeconds) +
            export("MANIFEST_SCHEMA_VERSION", MANIFEST_SCHEMA_VERSION) +
            // Recorded so a result can be traced to the image that produced it even if the
            // pointer has since moved on. /etc/baas-image-version, baked in, wins when present.
            export("IMAGE_VERSION", imageVersion) +
            export("AMI_ID", amiId) +
            export("RUNNER_JAR_S3_KEY", runnerJarS3Key) +
            export("RESULTS_TABLE", resultsTableName) +
            // Built by ResultKeys in the CLI and handed down verbatim. CREATED_AT above is
            // Instant.toString(), whose width varies, so a key the shell rebuilt from it would
            // address a different item from the one the CLI reserved.
            export("JOB_SORT_KEY", jobSortKey) +
            export("JOB_STATUS_GUARD", JobItemConditions.NOT_TERMINAL) +
            export("JOB_STATUS_GUARD_VALUES", guardValues()) +
            array("BENCHMARK_PARAMS_ARRAY", benchmarkParams) +
            array("RUNNER_TAGS_ARRAY", tagArgs) +
            "\n" +
            SCRIPT_BODY;

        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Every name the script shares with another runtime, taken from {@code baas-model} rather than
     * spelled here: the artifact names the CLI's readers look for, the job item's key and attribute
     * names the CLI's recorder writes, the runner's option names and the observed tag keys. The
     * script's text is the same either way; what changes is that a rename in the model reaches the
     * instance instead of compiling cleanly and failing a paid job. Done once, at class load.
     */
    private static String withContractNames(String body) {
        Map<String, String> names = new java.util.LinkedHashMap<>();
        names.put("@BOOT_LOG@", JobLayout.BOOT_LOG_NAME);
        names.put("@ENVIRONMENT@", JobLayout.ENVIRONMENT_NAME);
        names.put("@PACKAGES@", JobLayout.PACKAGES_NAME);
        names.put("@LOGS@", JobLayout.LOGS_SEGMENT);
        names.put("@PK@", MeasurementItemMapper.PK);
        names.put("@SK@", MeasurementItemMapper.SK);
        names.put("@JOB_PARTITION@", ResultKeys.JOB_PARTITION_KEY);
        names.put("@STATUS@", JobItemMapper.STATUS);
        names.put("@INSTANCE_ID@", JobItemMapper.INSTANCE_ID);
        names.put("@EXISTS@", JobItemConditions.EXISTS);
        names.put("@JOB_ID_FLAG@", RunnerArgs.JOB_ID);
        names.put("@CREATED_AT_FLAG@", RunnerArgs.CREATED_AT);
        names.put("@RESULT_PATH_FLAG@", RunnerArgs.RESULT_PATH);
        names.put("@S3_BUCKET_FLAG@", RunnerArgs.S3_BUCKET);
        names.put("@BENCHMARK_PATH_FLAG@", RunnerArgs.BENCHMARK_PATH);
        names.put("@RESULTS_TABLE_FLAG@", RunnerArgs.RESULTS_TABLE);
        names.put("@TAG_FLAG@", RunnerArgs.TAG);
        names.put("@IMAGE_VERSION_TAG@", TagKeys.IMAGE_VERSION);
        names.put("@INSTANCE_TYPE_TAG@", TagKeys.INSTANCE_TYPE);
        names.put("@JDK_TAG@", TagKeys.JDK);
        names.put("@JVM_VENDOR_TAG@", TagKeys.JVM_VENDOR);
        names.put("@CPU_MODEL_TAG@", TagKeys.CPU_MODEL);
        names.put("@CPU_ARCH_TAG@", TagKeys.CPU_ARCH);
        String rendered = body;
        for (var name : names.entrySet()) {
            rendered = rendered.replace(name.getKey(), name.getValue());
        }
        var unfilled = java.util.regex.Pattern.compile("@[A-Z_]+@").matcher(rendered);
        if (unfilled.find()) {
            throw new IllegalStateException("user-data template holds a name with no value: " + unfilled.group());
        }
        return rendered;
    }

    private static String withoutCommentLines(String body) {
        return body.lines()
            .filter(line -> !line.stripLeading().startsWith("#"))
            .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
    }

    /**
     * The values {@link JobItemConditions#NOT_TERMINAL} names, as the body of a JSON object, so
     * the shell's guard reads the same terminal set the CLI's does — from {@link JobStatus}.
     */
    static String guardValues() {
        return JobItemConditions.NOT_TERMINAL_VALUES.entrySet().stream()
            .map(e -> "\"" + e.getKey() + "\":{\"S\":\"" + e.getValue() + "\"}")
            .collect(java.util.stream.Collectors.joining(","));
    }

    /**
     * One of the two ways a value enters the script, {@link #array} being the other; every value
     * goes through one of them, machine-generated or not. An unclosed quote on any line makes bash
     * reject the whole script before the watchdog starts, so a value that is safe today only by
     * provenance is one rename away from orphaning a paid instance. Null exports as empty.
     */
    private static String export(String name, Object value) {
        return "export " + name + "=" + shellQuote(value != null ? value.toString() : "") + "\n";
    }

    /**
     * An argument vector as a bash array literal, one quoted element per argument. It replaced an
     * {@code eval} of a flat string, which parsed every argument a second time: a {@code $} or
     * backtick in a benchmark parameter expanded on the instance, and a {@code "} broke the line.
     * Arrays cannot be exported, which is fine — only this script reads them.
     */
    private static String array(String name, List<String> elements) {
        return name + "=(" + String.join(" ", elements.stream().map(UserDataScriptBuilder::shellQuote).toList())
            + ")\n";
    }

    /**
     * Single quotes make everything literal except {@code '} itself, which survives by closing
     * the quote, emitting an escaped quote and reopening it.
     */
    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
