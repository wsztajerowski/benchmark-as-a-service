package pl.wsztajerowski.baas.infra;

import pl.wsztajerowski.baas.model.TagKeys;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public class UserDataScriptBuilder {

    /** Bump when a field is added or renamed, so `baas env diff` can tell structure from content. */
    public static final int MANIFEST_SCHEMA_VERSION = 3;

    // Static script body — variables are prepended by build()
    private static final String SCRIPT_BODY = """
        TOKEN=$(curl -sX PUT "http://169.254.169.254/latest/api/token" \\
          -H "X-aws-ec2-metadata-token-ttl-seconds: 300")
        INSTANCE_ID=$(curl -sH "X-aws-ec2-metadata-token: $TOKEN" \\
          http://169.254.169.254/latest/meta-data/instance-id)

        # Layer 1: background watchdog (fires even if Java deadlocks)
        (
          sleep ${WALL_CLOCK_HARD_KILL}
          echo "WATCHDOG: hard-kill cap exceeded; terminating $INSTANCE_ID"
          # This path never reaches the normal upload below, and it is exactly the
          # case a user needs the log for — ship it before the instance disappears.
          aws s3 cp /var/log/cloud-init-output.log \\
            "s3://${S3_BUCKET}/${RESULT_PATH}/cloud-init-output.log" || true
          aws ec2 terminate-instances --instance-ids "$INSTANCE_ID" --region "${AWS_REGION}"
        ) &
        WATCHDOG_PID=$!

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
        # Written and uploaded BEFORE the benchmark, so a run that crashes still leaves a
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
        PERF_VERSION=$(json_escape "$(perf --version 2>/dev/null | head -1 || echo absent)")
        AWS_CLI_VERSION=$(json_escape "$(aws --version 2>&1 | head -1)")
        ASYNC_PROFILER_VERSION=$(json_escape "$(/app/async-profiler/bin/asprof --version 2>&1 | head -1 || echo absent)")
        PERF_EVENT_PARANOID=$(sysctl -n kernel.perf_event_paranoid 2>/dev/null)
        KPTR_RESTRICT=$(sysctl -n kernel.kptr_restrict 2>/dev/null)
        TRANSPARENT_HUGEPAGES=$(cat /sys/kernel/mm/transparent_hugepage/enabled 2>/dev/null)
        # The run's own identity. The run id is opaque by design, so what it stopped carrying the
        # manifest has to carry — and the manifest is written before the benchmark, so this is what
        # a run that dies early leaves behind. A project or branch name can contain " or \\.
        PROJECT=$(json_escape "${PROJECT_NAME}")
        BRANCH=$(json_escape "${BRANCH_NAME}")

        cat > /app/environment.json <<MANIFEST
        {
          "schemaVersion": ${MANIFEST_SCHEMA_VERSION},
          "imageVersion": "${IMAGE_VERSION_ACTUAL}",
          "amiId": "${AMI_ID}",
          "instanceType": "${INSTANCE_TYPE}",
          "region": "${AWS_REGION}",
          "cpuModel": "${CPU_MODEL}",
          "cpuArch": "${CPU_ARCH}",
          "cpuCores": "${CPU_CORES}",
          "cpuThreadsPerCore": "${CPU_THREADS_PER_CORE}",
          "cpuMaxMhz": "${CPU_MAX_MHZ}",
          "memoryTotalKb": "${MEMORY_TOTAL_KB}",
          "swapTotalKb": "${SWAP_TOTAL_KB}",
          "osVersion": "${OS_VERSION}",
          "kernelRelease": "${KERNEL_RELEASE}",
          "jvmVersion": "${JVM_VERSION}",
          "perfVersion": "${PERF_VERSION}",
          "awsCliVersion": "${AWS_CLI_VERSION}",
          "asyncProfilerVersion": "${ASYNC_PROFILER_VERSION}",
          "perfEventParanoid": "${PERF_EVENT_PARANOID}",
          "kptrRestrict": "${KPTR_RESTRICT}",
          "transparentHugepages": "${TRANSPARENT_HUGEPAGES}",
          "benchmarkType": "${BENCHMARK_TYPE}",
          "project": "${PROJECT}",
          "branch": "${BRANCH}",
          "requestId": "${REQUEST_ID}",
          "createdAt": "${CREATED_AT}"
        }
        MANIFEST

        # Several hundred lines, kept out of environment.json so its ~20 high-signal fields
        # stay readable.
        rpm -qa | sort > /app/packages.txt

        aws s3 cp /app/environment.json "s3://${S3_BUCKET}/${RESULT_PATH}/environment.json"
        aws s3 cp /app/packages.txt "s3://${S3_BUCKET}/${RESULT_PATH}/packages.txt"

        # Download runner JAR: from S3 (if --runner-jar provided) or GitHub Releases
        if [[ -n "${RUNNER_JAR_S3_KEY}" ]]; then
          aws s3 cp "s3://${S3_BUCKET}/${RUNNER_JAR_S3_KEY}" /app/benchmark-runner.jar
        else
          RELEASE_URL=$(curl -sH "Accept: application/vnd.github+json" \\
            "https://api.github.com/repos/wsztajerowski/benchmark-as-a-service/releases/latest" \\
            | grep '"browser_download_url"' | grep 'benchmark-runner\\.jar' | head -1 \\
            | sed 's/.*"browser_download_url": "\\(.*\\)".*/\\1/')
          wget -nv "${RELEASE_URL}" -O /app/benchmark-runner.jar
        fi

        aws s3 cp "s3://${S3_BUCKET}/${BENCHMARK_JAR_S3_KEY}" /app/benchmark-under-test.jar

        # Results store. The table name is not a secret — unlike the Mongo connection string
        # this replaced, it carries no credentials, so it travels in user-data instead of being
        # fetched from SSM at boot. Access is granted by RunnerRole, not by knowing the name.
        # Exactly one of the two is configured: `baas run` resolves the table from the stack
        # output and fails before provisioning when it cannot, so an empty table here means the
        # operator asked for --no-database. The runner treats absent store configuration as a
        # hard failure rather than a silent no-op, so never leave both unset.
        if [[ "${NO_DATABASE}" == "true" ]]; then
          STORE_ARGS=(--no-database)
        else
          STORE_ARGS=(--results-table "${RESULTS_TABLE}")
        fi

        # Layer 2: benchmark process with its own timeout
        # eval expands BENCHMARK_PARAMETERS (a double-quoted shell string) into an array
        # so params containing spaces are passed as single tokens to java.
        eval "BENCHMARK_PARAMS_ARRAY=(${BENCHMARK_PARAMETERS})"
        # Caller-supplied tags (project, commit, and any --tag the operator passed).
        # RunCommand.buildRunnerTags already rejects a caller tag whose key is
        # machine-observed (imageVersion, instanceType, jdk, cpuModel, cpuArch, type), so
        # this array should never actually collide with the five observed --tag lines
        # below. Same export-then-eval pattern as BENCHMARK_PARAMETERS, so values
        # containing spaces stay single argv tokens.
        eval "RUNNER_TAGS_ARRAY=(${RUNNER_TAGS})"
        # Tier 1 of the environment comparison: the five --tag lines below reach
        # benchmarkMetadata.tags, so `baas results` can flag a group whose rows sat on
        # different environments without fetching anything from S3. They are the values
        # OBSERVED above, not the ones the CLI passed down, so a result's tags cannot
        # disagree with its own environment.json. They are listed AFTER the caller-tags
        # expansion above, not before, as defence in depth: the runner parses --tag into
        # a picocli Map option, which is LAST-WINS on a duplicate key, so this order keeps
        # the observed value in charge even if a reserved key ever slips past the
        # CLI-side guard above.
        timeout "${BENCHMARK_TIMEOUT}" java -jar /app/benchmark-runner.jar "${BENCHMARK_TYPE}" \\
          --request-id     "${REQUEST_ID}" \\
          --created-at     "${CREATED_AT}" \\
          --result-path    "${RESULT_PATH}" \\
          --s3-bucket      "${S3_BUCKET}" \\
          --benchmark-path /app/benchmark-under-test.jar \\
          "${STORE_ARGS[@]}" \\
          "${RUNNER_TAGS_ARRAY[@]}" \\
          --tag "imageVersion=${IMAGE_VERSION_ACTUAL}" \\
          --tag "instanceType=${INSTANCE_TYPE}" \\
          --tag "jdk=${JDK_VERSION}" \\
          --tag "cpuModel=${CPU_MODEL_RAW}" \\
          --tag "cpuArch=${CPU_ARCH}" \\
          "${BENCHMARK_PARAMS_ARRAY[@]}"
        EXIT_CODE=$?

        # Write sentinel to S3
        STATUS="completed"; [[ $EXIT_CODE -ne 0 ]] && STATUS="failed:${EXIT_CODE}"
        echo "$STATUS" | aws s3 cp - "s3://${S3_BUCKET}/${RESULT_PATH}/run-status"

        # Ship the boot log before self-terminating — the instance is about to disappear
        # and this is the only record of what went wrong on a failed run.
        aws s3 cp /var/log/cloud-init-output.log \\
          "s3://${S3_BUCKET}/${RESULT_PATH}/cloud-init-output.log" || true

        # Cleanup
        kill $WATCHDOG_PID 2>/dev/null || true
        aws ec2 terminate-instances --instance-ids "$INSTANCE_ID" --region "${AWS_REGION}"
        """;

    public String build(String region, String bucket, String benchmarkType,
                        String requestId, String resultPath, String createdAt,
                        String benchmarkJarS3Key, int benchmarkTimeoutSeconds,
                        int wallClockHardKillSeconds, String imageVersion, String amiId,
                        String runnerJarS3Key, String resultsTableName, boolean noDatabase,
                        List<String> benchmarkParams, Map<String, String> runnerTags) {
        String params = String.join(" ", benchmarkParams.stream()
            .map(p -> p.contains(" ") ? "\"" + p + "\"" : p)
            .toList());

        // Each --tag "k=v" segment is re-parsed by the script's own
        // `eval "RUNNER_TAGS_ARRAY=(${RUNNER_TAGS})"` (see SCRIPT_BODY) — a SECOND shell parse,
        // distinct from the export-line parse the outer single-quote escaping below protects.
        // Inside that second parse, k/v sit in a double-quoted segment, where \, ", $ and ` are
        // still live metacharacters (double quotes suppress word-splitting and globbing, but NOT
        // command/variable substitution). An unescaped $(...) or ` there executes with RunnerRole's
        // IAM permissions — including the SSM read the operator policy deliberately withholds —
        // so every occurrence of those four characters must be backslash-escaped first, the same
        // way json_escape protects values destined for the double-quoted JSON manifest below.
        String tagArgs = runnerTags.entrySet().stream()
            .map(e -> "--tag \"" + escapeForEvaledDoubleQuote(e.getKey()) + "="
                + escapeForEvaledDoubleQuote(e.getValue()) + "\"")
            .collect(java.util.stream.Collectors.joining(" "));

        String script = "#!/bin/bash\n" +
            "# No set -e — errors handled explicitly so watchdog always starts\n" +
            "export AWS_REGION='" + region + "'\n" +
            "export S3_BUCKET='" + bucket + "'\n" +
            "export BENCHMARK_TYPE='" + benchmarkType + "'\n" +
            "export REQUEST_ID='" + requestId + "'\n" +
            "export RESULT_PATH='" + resultPath + "'\n" +
            // One clock read per run: this instant named the run's prefix and is what the runner
            // stores as createdAt, so the two cannot disagree. The instance's own clock is not
            // consulted.
            "export CREATED_AT='" + createdAt + "'\n" +
            "export BENCHMARK_JAR_S3_KEY='" + nullToEmpty(benchmarkJarS3Key) + "'\n" +
            // Only the manifest reads these two; the runner receives them as --tag instead, since
            // benchmarkMetadata.tags is the query surface baas results has.
            "export PROJECT_NAME='" + shellSingleQuote(nullToEmpty(project(runnerTags))) + "'\n" +
            "export BRANCH_NAME='" + shellSingleQuote(nullToEmpty(branch(runnerTags))) + "'\n" +
            "export BENCHMARK_TIMEOUT='" + benchmarkTimeoutSeconds + "'\n" +
            "export WALL_CLOCK_HARD_KILL='" + wallClockHardKillSeconds + "'\n" +
            "export MANIFEST_SCHEMA_VERSION='" + MANIFEST_SCHEMA_VERSION + "'\n" +
            // Recorded so a result can be traced to the image that produced it even if the
            // pointer has since moved on. /etc/baas-image-version, baked in, wins when present.
            "export IMAGE_VERSION='" + nullToEmpty(imageVersion) + "'\n" +
            "export AMI_ID='" + nullToEmpty(amiId) + "'\n" +
            "export RUNNER_JAR_S3_KEY='" + nullToEmpty(runnerJarS3Key) + "'\n" +
            "export RESULTS_TABLE='" + nullToEmpty(resultsTableName) + "'\n" +
            "export NO_DATABASE='" + noDatabase + "'\n" +
            "export BENCHMARK_PARAMETERS='" + params.replace("'", "'\\''") + "'\n" +
            "export RUNNER_TAGS='" + tagArgs.replace("'", "'\\''") + "'\n" +
            "\n" +
            SCRIPT_BODY;

        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private static String project(Map<String, String> runnerTags) {
        return runnerTags.get(TagKeys.PROJECT);
    }

    private static String branch(Map<String, String> runnerTags) {
        return runnerTags.get(TagKeys.BRANCH);
    }

    /**
     * Closes a single-quoted shell string, emits a literal quote and reopens it — the only way a
     * {@code '} survives inside {@code export X='…'}, and a branch name may well contain one.
     */
    private static String shellSingleQuote(String value) {
        return value.replace("'", "'\\''");
    }

    /**
     * Escapes a caller-supplied tag key/value so it survives, as literal text, the SECOND shell
     * parse performed by {@code eval "RUNNER_TAGS_ARRAY=(${RUNNER_TAGS})"} in SCRIPT_BODY. That
     * eval re-parses the segment as shell source, where the value sits inside a double-quoted
     * {@code "k=v"} token; \, ", $ and ` are the characters double quotes do NOT neutralize
     * (only word-splitting and globbing are suppressed), so each occurrence is backslash-escaped
     * — turning {@code $(cmd)}, {@code `cmd`} and {@code ${var}} into inert text instead of a
     * command/variable substitution, and letting a literal " or \\ round-trip unmolested.
     */
    private static String escapeForEvaledDoubleQuote(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '"' || c == '$' || c == '`') {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }
}
