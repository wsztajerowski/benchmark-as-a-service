package pl.wsztajerowski.baas.infra;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public class UserDataScriptBuilder {

    /** Bump when a field is added or renamed, so `baas env diff` can tell structure from content. */
    public static final int MANIFEST_SCHEMA_VERSION = 1;

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
        CPU_MODEL=$(json_escape "$(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2- | sed 's/^ *//')")
        CPU_CORES=$(nproc)
        CPU_THREADS_PER_CORE=$(lscpu_field "Thread")
        CPU_MAX_MHZ=$(lscpu_field "CPU max MHz")
        MEMORY_TOTAL_KB=$(awk '/MemTotal/ {print $2}' /proc/meminfo)
        SWAP_TOTAL_KB=$(awk '/SwapTotal/ {print $2}' /proc/meminfo)
        OS_VERSION=$(json_escape "$(. /etc/os-release && echo "$PRETTY_NAME")")
        KERNEL_RELEASE=$(uname -r)
        JVM_VERSION=$(json_escape "$(java -version 2>&1 | head -1)")
        PERF_VERSION=$(json_escape "$(perf --version 2>/dev/null | head -1 || echo absent)")
        AWS_CLI_VERSION=$(json_escape "$(aws --version 2>&1 | head -1)")
        ASYNC_PROFILER_VERSION=$(json_escape "$(/app/async-profiler/bin/asprof --version 2>&1 | head -1 || echo absent)")
        PERF_EVENT_PARANOID=$(sysctl -n kernel.perf_event_paranoid 2>/dev/null)
        KPTR_RESTRICT=$(sysctl -n kernel.kptr_restrict 2>/dev/null)
        TRANSPARENT_HUGEPAGES=$(cat /sys/kernel/mm/transparent_hugepage/enabled 2>/dev/null)

        cat > /app/environment.json <<MANIFEST
        {
          "schemaVersion": ${MANIFEST_SCHEMA_VERSION},
          "imageVersion": "${IMAGE_VERSION_ACTUAL}",
          "amiId": "${AMI_ID}",
          "instanceType": "${INSTANCE_TYPE}",
          "region": "${AWS_REGION}",
          "cpuModel": "${CPU_MODEL}",
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
          "benchmarkType": "${BENCHMARK_TYPE}"
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

        aws s3 cp "s3://${S3_BUCKET}/runs/${REQUEST_ID}/benchmark.jar" /app/benchmark-under-test.jar

        # Fetch MongoDB URI from SSM (never stored in user-data)
        export MONGO_CONNECTION_STRING=$(aws ssm get-parameter \\
          --name "/${SSM_PREFIX}/mongo/connection-string" \\
          --with-decryption --query Parameter.Value --output text --region "${AWS_REGION}")

        # Layer 2: benchmark process with its own timeout
        # eval expands BENCHMARK_PARAMETERS (a double-quoted shell string) into an array
        # so params containing spaces are passed as single tokens to java.
        eval "BENCHMARK_PARAMS_ARRAY=(${BENCHMARK_PARAMETERS})"
        # Caller-supplied tags (project, commit, and any --tag the operator passed). Same
        # export-then-eval pattern as BENCHMARK_PARAMETERS, so values containing spaces stay
        # single argv tokens. Instance-observed tags are appended separately below.
        eval "RUNNER_TAGS_ARRAY=(${RUNNER_TAGS})"
        # Tier 1 of the environment comparison: these two reach benchmarkMetadata.tags, so
        # `baas results` can flag a group whose rows sat on different environments without
        # fetching anything from S3. They are the values OBSERVED above, not the ones the CLI
        # passed down, so a result's tags cannot disagree with its own environment.json.
        timeout "${BENCHMARK_TIMEOUT}" java -jar /app/benchmark-runner.jar "${BENCHMARK_TYPE}" \\
          --request-id     "${REQUEST_ID}" \\
          --result-path    "${RESULT_PATH}" \\
          --s3-bucket      "${S3_BUCKET}" \\
          --benchmark-path /app/benchmark-under-test.jar \\
          --tag "imageVersion=${IMAGE_VERSION_ACTUAL}" \\
          --tag "instanceType=${INSTANCE_TYPE}" \\
          "${RUNNER_TAGS_ARRAY[@]}" \\
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

    public String build(String region, String bucket, String ssmPrefix, String benchmarkType,
                        String requestId, String resultPath, int benchmarkTimeoutSeconds,
                        int wallClockHardKillSeconds, String imageVersion, String amiId,
                        String runnerJarS3Key, List<String> benchmarkParams,
                        Map<String, String> runnerTags) {
        String params = String.join(" ", benchmarkParams.stream()
            .map(p -> p.contains(" ") ? "\"" + p + "\"" : p)
            .toList());

        String tagArgs = runnerTags.entrySet().stream()
            .map(e -> "--tag \"" + e.getKey() + "=" + e.getValue() + "\"")
            .collect(java.util.stream.Collectors.joining(" "));

        String script = "#!/bin/bash\n" +
            "# No set -e — errors handled explicitly so watchdog always starts\n" +
            "export AWS_REGION='" + region + "'\n" +
            "export S3_BUCKET='" + bucket + "'\n" +
            "export SSM_PREFIX='" + ssmPrefix + "'\n" +
            "export BENCHMARK_TYPE='" + benchmarkType + "'\n" +
            "export REQUEST_ID='" + requestId + "'\n" +
            "export RESULT_PATH='" + resultPath + "'\n" +
            "export BENCHMARK_TIMEOUT='" + benchmarkTimeoutSeconds + "'\n" +
            "export WALL_CLOCK_HARD_KILL='" + wallClockHardKillSeconds + "'\n" +
            "export MANIFEST_SCHEMA_VERSION='" + MANIFEST_SCHEMA_VERSION + "'\n" +
            // Recorded so a result can be traced to the image that produced it even if the
            // pointer has since moved on. /etc/baas-image-version, baked in, wins when present.
            "export IMAGE_VERSION='" + nullToEmpty(imageVersion) + "'\n" +
            "export AMI_ID='" + nullToEmpty(amiId) + "'\n" +
            "export RUNNER_JAR_S3_KEY='" + nullToEmpty(runnerJarS3Key) + "'\n" +
            "export BENCHMARK_PARAMETERS='" + params.replace("'", "'\\''") + "'\n" +
            "export RUNNER_TAGS='" + tagArgs.replace("'", "'\\''") + "'\n" +
            "\n" +
            SCRIPT_BODY;

        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
