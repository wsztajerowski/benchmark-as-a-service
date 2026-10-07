package pl.wsztajerowski.baas.infra;

import pl.wsztajerowski.baas.model.JobStatus;
import pl.wsztajerowski.baas.model.TagKeys;
import pl.wsztajerowski.baas.jobs.DynamoDbJobRecorder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class UserDataScriptBuilder {

    /** Bump when a field is added or renamed, so `baas env diff` can tell structure from content. */
    public static final int MANIFEST_SCHEMA_VERSION = 6;

    /**
     * EC2 refuses user-data over 16 KB raw, and the launch then fails outright. The comments below
     * document the script for whoever reads this file, not for the instance, so comment-only lines
     * are dropped before the script is rendered — they were most of its size. Nothing in the body
     * may therefore depend on a comment line surviving; the heredoc holds none.
     */
    static final int USER_DATA_LIMIT_BYTES = 16 * 1024;

    // Static script body — variables are prepended by build()
    private static final String SCRIPT_BODY = withoutCommentLines("""
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
            names='{"#status":"status","#instanceId":"instanceId"}'
            values='{":s":{"S":"'"${status}"'"},":iid":{"S":"'"${INSTANCE_ID}"'"},'"${JOB_STATUS_GUARD_VALUES}"'}'
          else
            update='SET #status = :s'
            names='{"#status":"status"}'
            values='{":s":{"S":"'"${status}"'"},'"${JOB_STATUS_GUARD_VALUES}"'}'
          fi
          err=$(AWS_RETRY_MODE=standard AWS_MAX_ATTEMPTS="${attempts}" aws dynamodb update-item \
            --region "${AWS_REGION}" --table-name "${RESULTS_TABLE}" \
            --key '{"pk":{"S":"JOB"},"sk":{"S":"'"${JOB_SORT_KEY}"'"}}' \
            --update-expression "${update}" \
            --condition-expression "attribute_exists(pk) AND ${JOB_STATUS_GUARD}" \
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
          # case a user needs the log for — ship it before the instance disappears.
          aws s3 cp /var/log/cloud-init-output.log \\
            "s3://${S3_BUCKET}/${RESULT_PATH}/cloud-init-output.log" || true
          aws ec2 terminate-instances --instance-ids "$INSTANCE_ID" --region "${AWS_REGION}"
        ) &
        WATCHDOG_PID=$!

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
          aws s3 cp /var/log/cloud-init-output.log \\
            "s3://${S3_BUCKET}/${RESULT_PATH}/cloud-init-output.log" || true
          kill $WATCHDOG_PID 2>/dev/null || true
          aws ec2 terminate-instances --instance-ids "$INSTANCE_ID" --region "${AWS_REGION}"
          exit 0
        fi

        # Nothing is installed here. Corretto, perf, the AWS CLI and async-profiler are baked
        # into the AMI by `baas admin build-image` from infra/runner-image.yaml — a runner that
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
        # mistake in any of them produces a file that only fails weeks later in `baas env diff`.
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
        CPU_CORES=$(nproc)
        CPU_THREADS_PER_CORE=$(lscpu_field "Thread")
        CPU_MAX_MHZ=$(lscpu_field "CPU max MHz")
        MEMORY_TOTAL_KB=$(awk '/MemTotal/ {print $2}' /proc/meminfo)
        SWAP_TOTAL_KB=$(awk '/SwapTotal/ {print $2}' /proc/meminfo)
        OS_VERSION=$(json_escape "$(. /etc/os-release && echo "$PRETTY_NAME")")
        KERNEL_RELEASE=$(uname -r)
        JVM_VERSION_RAW=$(java -version 2>&1 | head -1)
        JVM_VERSION=$(json_escape "$JVM_VERSION_RAW")
        # jdk tag: same observation as JVM_VERSION_RAW above, projected to the bare version
        # number (e.g. "25") instead of the full escaped line — not a second `java -version`.
        JDK_VERSION=$(printf '%s' "$JVM_VERSION_RAW" | sed -n 's/.*"\\(.*\\)".*/\\1/p')
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
        # The job's own identity. The job id is opaque by design, so what it stopped carrying the
        # manifest has to carry — and the manifest is written before the benchmark, so this is what
        # a job that dies early leaves behind. A project or branch name can contain " or \\.

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
            "cores": "${CPU_CORES}",
            "threadsPerCore": "${CPU_THREADS_PER_CORE}",
            "maxMhz": "${CPU_MAX_MHZ}"
          },
          "memory": {
            "totalKb": "${MEMORY_TOTAL_KB}",
            "swapTotalKb": "${SWAP_TOTAL_KB}"
          },
          "os": {
            "version": "${OS_VERSION}",
            "kernelRelease": "${KERNEL_RELEASE}"
          },
          "jvm": {
            "version": "${JVM_VERSION}",
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

        aws s3 cp /app/environment.json "s3://${S3_BUCKET}/${RESULT_PATH}/environment.json"
        aws s3 cp /app/packages.txt "s3://${S3_BUCKET}/${RESULT_PATH}/packages.txt"

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
        # one quoted element per argument, so bash parses them once, as data — no eval.
        # RunCommand.buildRunnerTags already rejects a caller tag whose key is
        # machine-observed (imageVersion, instanceType, jdk, jvmVendor, cpuModel, cpuArch, type),
        # so the caller tags should never actually collide with the six observed --tag lines
        # below.
        # The six --tag lines below reach the item's tags map, so `baas results` can filter
        # and group by the environment without fetching anything from S3. They are the values
        # OBSERVED above, not the ones the CLI passed down, so a result's tags cannot
        # disagree with its own environment.json. They are listed AFTER the caller-tags
        # expansion above, not before, as defence in depth: the runner parses --tag into
        # a picocli Map option, which is LAST-WINS on a duplicate key, so this order keeps
        # the observed value in charge even if a reserved key ever slips past the
        # CLI-side guard above.
        timeout "${BENCHMARK_TIMEOUT}" java -jar /app/benchmark-runner.jar "${BENCHMARK_TYPE}" \\
          --job-id     "${JOB_ID}" \\
          --created-at     "${CREATED_AT}" \\
          --result-path    "${RESULT_PATH}" \\
          --s3-bucket      "${S3_BUCKET}" \\
          --benchmark-path /app/benchmark-under-test.jar \\
          --results-table  "${RESULTS_TABLE}" \\
          "${RUNNER_TAGS_ARRAY[@]}" \\
          --tag "imageVersion=${IMAGE_VERSION_ACTUAL}" \\
          --tag "instanceType=${INSTANCE_TYPE}" \\
          --tag "jdk=${JDK_VERSION}" \\
          --tag "jvmVendor=${JVM_VENDOR_RAW}" \\
          --tag "cpuModel=${CPU_MODEL_RAW}" \\
          --tag "cpuArch=${CPU_ARCH}" \\
          "${BENCHMARK_PARAMS_ARRAY[@]}"
        EXIT_CODE=$?

        # The outcome, on the job item — what `baas run` polls and `baas jobs list` shows.
        STATUS="completed"; [[ $EXIT_CODE -ne 0 ]] && STATUS="failed:${EXIT_CODE}"
        job_status "${STATUS}"

        # Ship the boot log before self-terminating — the instance is about to disappear
        # and this is the only record of what went wrong on a failed job.
        aws s3 cp /var/log/cloud-init-output.log \\
          "s3://${S3_BUCKET}/${RESULT_PATH}/cloud-init-output.log" || true

        # Cleanup
        kill $WATCHDOG_PID 2>/dev/null || true
        aws ec2 terminate-instances --instance-ids "$INSTANCE_ID" --region "${AWS_REGION}"
        """);

    public String build(String region, String bucket, String benchmarkType,
                        String jobId, String resultPath, String createdAt,
                        String benchmarkJarS3Key, int benchmarkTimeoutSeconds,
                        int wallClockHardKillSeconds, String imageVersion, String amiId,
                        String runnerJarS3Key, String resultsTableName, String jobSortKey,
                        List<String> benchmarkParams, Map<String, String> runnerTags) {
        List<String> tagArgs = runnerTags.entrySet().stream()
            .flatMap(e -> Stream.of("--tag", e.getKey() + "=" + e.getValue()))
            .toList();

        String script = "#!/bin/bash\n" +
            "# No set -e — errors handled explicitly so watchdog always starts\n" +
            export("AWS_REGION", region) +
            export("S3_BUCKET", bucket) +
            export("BENCHMARK_TYPE", benchmarkType) +
            export("JOB_ID", jobId) +
            export("RESULT_PATH", resultPath) +
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
            export("JOB_STATUS_GUARD", DynamoDbJobRecorder.NOT_TERMINAL) +
            export("JOB_STATUS_GUARD_VALUES", guardValues()) +
            array("BENCHMARK_PARAMS_ARRAY", benchmarkParams) +
            array("RUNNER_TAGS_ARRAY", tagArgs) +
            "\n" +
            SCRIPT_BODY;

        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
    }

    private static String withoutCommentLines(String body) {
        return body.lines()
            .filter(line -> !line.stripLeading().startsWith("#"))
            .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
    }

    /**
     * The values {@link DynamoDbJobRecorder#NOT_TERMINAL} names, as the body of a JSON object, so
     * the shell's guard reads the same terminal set the CLI's does — from {@link JobStatus}.
     */
    static String guardValues() {
        return Stream.of(
                Map.entry(":completed", JobStatus.COMPLETED),
                Map.entry(":timedOut", JobStatus.TIMED_OUT),
                Map.entry(":cancelled", JobStatus.CANCELLED),
                Map.entry(":launchFailed", JobStatus.LAUNCH_FAILED),
                Map.entry(":failedPrefix", JobStatus.FAILED_PREFIX))
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
