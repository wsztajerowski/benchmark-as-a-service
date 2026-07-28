package pl.wsztajerowski.baas.infra;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

public class UserDataScriptBuilder {

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

        # Install runtime
        yum update -y
        yum install -y java-25-amazon-corretto-headless

        # async-profiler (jmh-with-async only)
        if [[ "${BENCHMARK_TYPE}" == "jmh-with-async" ]]; then
          mkdir -p /app
          wget -nv "https://github.com/async-profiler/async-profiler/releases/download/v${ASYNC_PROFILER_VERSION}/async-profiler-${ASYNC_PROFILER_VERSION}-linux-x64.tar.gz" -O /tmp/ap.tar.gz
          tar -xf /tmp/ap.tar.gz -C /tmp
          mv /tmp/async-profiler-*-linux-x64 /app/async-profiler
        fi

        mkdir -p /app
        # Run from a real working directory. cloud-init starts us in /, and the runner
        # scans the tree below its cwd for .log files to upload — from / that means
        # walking the whole root filesystem, and dying on /proc entries that vanish
        # mid-walk. The GitHub Actions flow this replaced ran from its workspace dir.
        cd /app

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
        timeout "${BENCHMARK_TIMEOUT}" java -jar /app/benchmark-runner.jar "${BENCHMARK_TYPE}" \\
          --request-id     "${REQUEST_ID}" \\
          --result-path    "${RESULT_PATH}" \\
          --s3-bucket      "${S3_BUCKET}" \\
          --benchmark-path /app/benchmark-under-test.jar \\
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
                        int wallClockHardKillSeconds, String asyncProfilerVersion,
                        String runnerJarS3Key, List<String> benchmarkParams) {
        String params = String.join(" ", benchmarkParams.stream()
            .map(p -> p.contains(" ") ? "\"" + p + "\"" : p)
            .toList());

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
            "export ASYNC_PROFILER_VERSION='" + asyncProfilerVersion + "'\n" +
            "export RUNNER_JAR_S3_KEY='" + (runnerJarS3Key != null ? runnerJarS3Key : "") + "'\n" +
            "export BENCHMARK_PARAMETERS='" + params.replace("'", "'\\''") + "'\n" +
            "\n" +
            SCRIPT_BODY;

        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
    }
}
