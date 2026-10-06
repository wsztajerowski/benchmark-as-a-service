package pl.wsztajerowski.baas.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class UserDataScriptBuilderTest {

    @TempDir
    Path tempDir;

    private String script() {
        return script(Map.of());
    }

    private String script(Map<String, String> runnerTags) {
        return script(runnerTags, "baas-a1b2c3d4-results");
    }

    private static final String JOB_SORT_KEY = "2026-07-24T12:00:00.000Z#20260724T120000000Z-a3f9c21b";

    private String script(Map<String, String> runnerTags, String resultsTable) {
        String encoded = new UserDataScriptBuilder().build(
            "eu-central-1", "baas-a1b2c3d4", "jmh",
            "20260724T120000000Z-a3f9c21b",
            "jobs/lynx-journal/20260724T120000000Z-a3f9c21b",
            "2026-07-24T12:00:00Z",
            "jobs/lynx-journal/20260724T120000000Z-a3f9c21b/input/benchmark.jar",
            7200, 7500,
            "1.0.0", "ami-0123456789abcdef0", null, resultsTable, JOB_SORT_KEY,
            List.of("MyBenchmark", "-f", "1"), runnerTags);
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    /**
     * Actually runs the generated {@code RUNNER_TAGS_ARRAY=(...)} line through bash and returns the
     * resulting array elements — what the runner's argv will hold.
     *
     * A substring check on the rendered script text cannot catch a quoting defect: the line is
     * always "correct" by construction (it's just Java string concatenation), and the bug only
     * exists in what bash makes of it. Only executing it proves the fix.
     */
    private List<String> evaluateRunnerTagsArray(Map<String, String> runnerTags) throws Exception {
        return evaluateArray(script(runnerTags), "RUNNER_TAGS_ARRAY");
    }

    private List<String> evaluateArray(String script, String name) throws Exception {
        String arrayLine = script.lines()
            .filter(l -> l.startsWith(name + "=("))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no " + name + " line in generated script"));

        String harness = arrayLine + "\n"
            + "for element in \"${" + name + "[@]}\"; do printf '%s\\n' \"$element\"; done\n";

        Process process = new ProcessBuilder("bash", "-c", harness).start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        assertThat(finished).as("bash harness must not hang").isTrue();
        assertThat(process.exitValue()).as("bash harness stderr: " + stderr).isZero();

        return stdout.lines().toList();
    }

    @Test
    void shipsCloudInitLogToS3BeforeTerminating() {
        String script = script();

        assertThat(script)
            .as("the instance self-terminates, so an unshipped log is a log that no longer exists")
            .contains("/var/log/cloud-init-output.log")
            .contains("${RESULT_PATH}/cloud-init-output.log");

        assertThat(script.indexOf("cloud-init-output.log"))
            .as("the upload must happen before the terminate call")
            .isLessThan(script.lastIndexOf("terminate-instances"));
    }

    /**
     * The watchdog fires on the paths where the log matters most — a deadlocked JVM or a
     * hung download — and it terminates the instance without ever reaching the normal
     * upload further down the script. `baas run` points users at that S3 key, so it has
     * to exist on this path too.
     */
    @Test
    void watchdogShipsTheLogBeforeHardKillingTheInstance() {
        String script = script();

        int watchdogStart = script.indexOf("sleep ${WALL_CLOCK_HARD_KILL}");
        int watchdogTerminate = script.indexOf("terminate-instances", watchdogStart);
        int uploadInWatchdog = script.indexOf("cloud-init-output.log", watchdogStart);

        assertThat(watchdogStart).isNotNegative();
        assertThat(uploadInWatchdog)
            .as("the watchdog kills the instance without reaching the normal upload")
            .isNotNegative()
            .isLessThan(watchdogTerminate);
    }

    /**
     * The watchdog is the only termination layer that survives a deadlocked JVM, and every
     * failure after this point depends on it already running.
     */
    @Test
    void watchdogStartsImmediatelyAfterTheInstanceIdResolves() {
        String script = script();

        assertThat(script.indexOf("WATCHDOG_PID=$!"))
            .as("nothing that can fail may sit between resolving INSTANCE_ID and arming the watchdog")
            .isGreaterThan(script.indexOf("INSTANCE_ID=$("))
            .isLessThan(script.indexOf("environment.json"));
    }

    /**
     * Under {@code set -e} a failed IMDSv2 fetch exits before the watchdog starts and orphans a
     * paid instance. Errors are handled by exit code and the job item's status instead.
     */
    @Test
    void hasNoSetE() {
        assertThat(script().lines().map(String::strip))
            .noneMatch(line -> line.equals("set -e") || line.startsWith("set -e "))
            .contains("# No set -e — errors handled explicitly so watchdog always starts");
    }

    /**
     * cloud-init starts the script in /, and the runner walks the tree below its cwd looking
     * for .log files to upload. From / that walks the entire root filesystem and aborts on
     * /proc entries that disappear mid-walk, which fails the job after the benchmark has
     * already completed.
     */
    @Test
    void runsTheBenchmarkFromARealWorkingDirectory() {
        String script = script();

        assertThat(script).contains("cd /app");
        assertThat(script.indexOf("cd /app"))
            .as("the working directory has to be set before the runner starts")
            .isLessThan(script.indexOf("java -jar /app/benchmark-runner.jar"));
    }

    @Test
    void passesBenchmarkParametersThrough() {
        assertThat(script()).contains("BENCHMARK_PARAMS_ARRAY=('MyBenchmark' '-f' '1')");
    }

    /**
     * S6: parameters used to travel as one string and be re-split by {@code eval}, which expanded
     * {@code $} and backticks on the instance and broke on a {@code "}. Every character must reach
     * the runner as typed, one argument per parameter.
     */
    @Test
    void benchmarkParametersReachTheRunnerExactlyAsTyped() throws Exception {
        Path marker = tempDir.resolve("PWNED");
        List<String> params = List.of("MyBenchmark", "-p", "pattern=a$b", "-jvmArgs", "-Dx=\"q\" `id`",
            "$(touch " + marker + ")", "it's", "two words", "back\\slash", "a;b");
        String encoded = new UserDataScriptBuilder().build(
            "eu-central-1", "baas-a1b2c3d4", "jmh", "id", "jobs/p/id", "2026-07-24T12:00:00Z",
            "jobs/p/id/input/benchmark.jar", 7200, 7500, "1.0.0", "ami-0", null, "t", JOB_SORT_KEY,
            params, Map.of());

        List<String> elements = evaluateArray(
            new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8), "BENCHMARK_PARAMS_ARRAY");

        assertThat(Files.exists(marker)).as("a parameter must never run as shell").isFalse();
        assertThat(elements).containsExactlyElementsOf(params);
    }

    // ─── Prebaked image: nothing is installed at run time ────────────────────────

    @Test
    void installsNothing() {
        String script = script();

        assertThat(script)
            .as("every install at boot is a machine that differs from the last one measured on")
            .doesNotContain("yum")
            .doesNotContain("dnf install")
            .doesNotContain("async-profiler/releases/download");
    }

    // ─── Results store ───────────────────────────────────────────────────────────

    /**
     * The cutover. The table name is not a secret — no credentials, access granted by RunnerRole
     * — so unlike the Mongo connection string it replaced it travels in user-data instead of
     * costing an SSM round trip on every boot.
     */
    @Test
    void passesTheResultsTableToTheRunner() {
        String script = script();

        assertThat(script).contains("export RESULTS_TABLE='baas-a1b2c3d4-results'");
        assertThat(script).contains("--results-table  \"${RESULTS_TABLE}\" \\");
    }

    /**
     * Not cosmetic: while this fetch existed the runner picked up MONGO_CONNECTION_STRING from
     * the environment and wrote to Atlas, so a leftover line here would send measurements to a
     * store the CLI can no longer read — and the job would still report success.
     */
    @Test
    void fetchesNoMongoConnectionStringFromSsm() {
        String script = script();

        assertThat(script)
            .doesNotContain("MONGO_CONNECTION_STRING")
            .doesNotContain("mongo/connection-string")
            .doesNotContain("ssm get-parameter")
            .doesNotContain("SSM_PREFIX");
    }

    /**
     * Every job names the table: its status is recorded there too, so there is no job without one,
     * and the runner is never told to discard measurements.
     */
    @Test
    void alwaysPassesTheResultsTableAndNeverDiscards() {
        String script = script();
        assertThat(script).doesNotContain("NO_DATABASE").doesNotContain("--no-database");
        assertThat(script.lines().filter(l -> l.strip().startsWith("--results-table")))
            .as("the runner is named exactly one store, unconditionally")
            .hasSize(1);
    }

    // ─── Job status on the job item ──────────────────────────────────────────────

    @Test
    void writesNoJobStatusObjectToS3() {
        assertThat(script()).doesNotContain("/run-status");
    }

    /** Built by ResultKeys in the CLI; the shell must not rebuild it from CREATED_AT. */
    @Test
    void theJobItemKeyArrivesVerbatim() {
        String script = script();

        assertThat(script).contains("export JOB_SORT_KEY='" + JOB_SORT_KEY + "'");
        assertThat(script).contains(
            "--key '{\"pk\":{\"S\":\"JOB\"},\"sk\":{\"S\":\"'\"${JOB_SORT_KEY}\"'\"}}'");
        assertThat(script).doesNotContain("${CREATED_AT}#");
    }

    /** The shell's guard is the CLI's own expression and values, so "terminal" means one thing. */
    @Test
    void theTerminalGuardIsTheCLIsOwn() {
        String script = script();

        assertThat(script).contains("export JOB_STATUS_GUARD='" + pl.wsztajerowski.baas.jobs.DynamoDbJobRecorder.NOT_TERMINAL + "'");
        assertThat(script).contains("--condition-expression \"attribute_exists(pk) AND ${JOB_STATUS_GUARD}\"");
        assertThat(UserDataScriptBuilder.guardValues())
            .contains("\":completed\":{\"S\":\"completed\"}", "\":failedPrefix\":{\"S\":\"failed:\"}",
                "\":timedOut\":{\"S\":\"timed-out\"}", "\":cancelled\":{\"S\":\"cancelled\"}",
                "\":launchFailed\":{\"S\":\"launch-failed\"}");
    }

    /**
     * The watchdog is a forked subshell, which sees only the functions defined before the fork:
     * defined after it, `job_status timed-out` would be "command not found" on exactly the path
     * it exists for — and {@code bash -n} would not notice.
     */
    @Test
    void jobStatusIsDefinedBeforeTheWatchdogForks() {
        String script = script();

        assertThat(script.indexOf("job_status() {"))
            .isPositive()
            .isLessThan(script.indexOf("INSTANCE_ID=$("))
            .isLessThan(script.indexOf("sleep ${WALL_CLOCK_HARD_KILL}"));
    }

    @Test
    void recordsRunningOnlyAfterTheWatchdogStarts() {
        String script = script();

        assertThat(script.indexOf("job_status running"))
            .isGreaterThan(script.indexOf("WATCHDOG_PID=$!"))
            .isLessThan(script.indexOf("java -jar /app/benchmark-runner.jar"));
    }

    @Test
    void recordsTheOutcomeWhereTheSentinelUsedToBe() {
        String script = script();
        int benchmark = script.indexOf("java -jar /app/benchmark-runner.jar");

        assertThat(script.indexOf("job_status \"${STATUS}\"", benchmark))
            .isPositive()
            .isLessThan(script.lastIndexOf("cloud-init-output.log"));
    }

    /** The runner's Java SDK reads the same variables; exporting them would change its retries. */
    @Test
    void retrySettingsApplyToTheStatusCommandOnly() {
        String script = script();

        assertThat(script.lines().map(String::strip))
            .noneMatch(l -> l.startsWith("export AWS_MAX_ATTEMPTS") || l.startsWith("export AWS_RETRY_MODE"));
        assertThat(script).contains(
            "err=$(AWS_RETRY_MODE=standard AWS_MAX_ATTEMPTS=\"${attempts}\" aws dynamodb update-item");
        assertThat(script).contains("local status=\"$1\" attempts=\"${2:-3}\"");
        // 2 attempts x (5 s connect + 10 s read) + up to 2 s of standard-mode backoff = ~32 s for the
        // one write on the path to the JVM, inside the 60 s minimum watchdog margin. Three attempts
        // would be ~51 s, leaving the boot, the manifest and four downloads under 10 s.
        assertThat(script).contains("--cli-connect-timeout 5 --cli-read-timeout 10");
        assertThat(script).contains("job_status running 2\n");
    }

    /**
     * Executes the rendered block after the {@code running} write against a stub {@code aws}. A
     * refusal — the job was cancelled while its instance was launching, or has no item — must stop
     * the instance before the benchmark: the CLI may have cancelled it without finding the instance,
     * and nothing else would then end it before its timeout.
     */
    @Test
    void aRefusedRunningWriteShipsTheLogAndTerminatesInsteadOfRunningTheBenchmark() throws Exception {
        String block = runningWriteBlock(script());

        List<String> calls = runAgainstStubAws(script(), "conditional",
            "INSTANCE_ID=i-0abc\n" + block + "\necho benchmark-started >> \"$STUB_CALLS\"\n");

        assertThat(calls).hasSize(3);
        assertThat(calls.get(0)).startsWith("dynamodb update-item").contains("running");
        assertThat(calls.get(1)).startsWith("s3 cp /var/log/cloud-init-output.log");
        assertThat(calls.get(2)).startsWith("ec2 terminate-instances").contains("i-0abc");
    }

    /** Only a refusal stops the job; a table that cannot be reached is no reason to waste the instance. */
    @Test
    void aRecordedOrFailedRunningWriteLetsTheBenchmarkStart() throws Exception {
        String block = runningWriteBlock(script());

        for (String mode : List.of("ok", "fail")) {
            List<String> calls = runAgainstStubAws(script(), mode,
                "INSTANCE_ID=i-0abc\n" + block + "\necho benchmark-started >> \"$STUB_CALLS\"\n");

            assertThat(calls).as(mode).hasSize(2);
            assertThat(calls.get(0)).as(mode).startsWith("dynamodb update-item");
            assertThat(calls.get(1)).as(mode).isEqualTo("benchmark-started");
        }
    }

    private static String runningWriteBlock(String script) {
        int start = script.indexOf("job_status running 2");
        assertThat(start).as("running write").isPositive();
        return script.substring(start, script.indexOf("\nfi\n", start) + "\nfi\n".length());
    }

    /**
     * EC2 rejects user-data over 16 KB raw, and every such run ends {@code launch-failed}. A long
     * {@code -p} sweep or a large {@code --tag options=...} is what grows it, so this renders an
     * outsized but plausible run and holds it well under the limit.
     */
    @Test
    void aLargeJobStaysWellUnderTheUserDataLimit() {
        Map<String, String> tags = new java.util.LinkedHashMap<>();
        tags.put("project", "benchmark-as-a-service");
        tags.put("branch", "feature/some-fairly-long-branch-name-for-a-change");
        tags.put("commit", "0123456789abcdef0123456789abcdef01234567");
        tags.put("source", "ci");
        tags.put("exclude_from_results", "true");
        tags.put("options", "-prof async:event=cpu;output=flamegraph;dir=/app/async-output -jvmArgs -Xmx4g");
        List<String> params = List.of("pl.wsztajerowski.SomeBenchmark.measure", "-f", "3", "-wi", "5", "-i", "10",
            "-p", "size=1,10,100,1000,10000,100000", "-p", "implementation=synchronized,reentrant,stamped,atomic",
            "-p", "threads=1,2,4,8,16", "-jvmArgs", "-XX:+UseG1GC -Xms4g -Xmx4g");
        String encoded = new UserDataScriptBuilder().build(
            "eu-central-1", "baas-381492019823", "jmh-with-async",
            "20260724T120000000Z-a3f9c21b",
            "jobs/benchmark-as-a-service/20260724T120000000Z-a3f9c21b",
            "2026-07-24T12:00:00.123Z",
            "jobs/benchmark-as-a-service/20260724T120000000Z-a3f9c21b/input/benchmark.jar",
            7200, 7500, "1.2.0", "ami-0ae60d9d990dc00df", "releases/6.1.0/benchmark-runner.jar",
            "baas-381492019823-results", JOB_SORT_KEY, params, tags);
        int rawBytes = Base64.getDecoder().decode(encoded).length;

        assertThat(rawBytes).isLessThan(UserDataScriptBuilder.USER_DATA_LIMIT_BYTES * 3 / 4);
    }

    /** The comments are most of the source's size, and the instance has no use for them. */
    @Test
    void commentLinesAreNotRendered() {
        assertThat(script().lines().skip(2).map(String::stripLeading))
            .noneMatch(line -> line.startsWith("#"));
    }

    /**
     * Executes the rendered watchdog subshell itself, with its delay set to zero and a stub
     * {@code aws} that records each call. Proves the function is visible inside the forked
     * subshell and that the outcome is recorded before the log upload and the termination.
     */
    @Test
    void theWatchdogRecordsTimedOutBeforeShippingTheLogAndTerminating() throws Exception {
        String script = script();
        int start = script.indexOf("(\n  sleep ${WALL_CLOCK_HARD_KILL}");
        int end = script.indexOf(") &", start) + ") &".length();
        assertThat(start).as("watchdog block").isPositive();

        List<String> calls = runAgainstStubAws(script, "ok",
            "WALL_CLOCK_HARD_KILL=0\nINSTANCE_ID=i-0abc\n" + script.substring(start, end) + "\nwait\n");

        assertThat(calls).hasSize(3);
        assertThat(calls.get(0)).startsWith("dynamodb update-item").contains("timed-out").contains("i-0abc");
        assertThat(calls.get(1)).startsWith("s3 cp /var/log/cloud-init-output.log");
        assertThat(calls.get(2)).startsWith("ec2 terminate-instances");
    }

    @Test
    void aRefusedStatusWriteIsExpectedAndAFailedOneIsLoggedButNeitherStopsTheScript() throws Exception {
        String script = script();

        assertThat(jobStatusOutput(script, "ok")).contains("job_status: completed").contains("after");
        assertThat(jobStatusOutput(script, "conditional"))
            .contains("job_status: completed not recorded: status already terminal, or no job item at this key")
            .contains("after");
        assertThat(jobStatusOutput(script, "fail"))
            .contains("job_status: completed not recorded (exit 255): boom")
            .contains("after");
    }

    /** Without an instance id (IMDS failed) the write still lands, just without that attribute. */
    @Test
    void theStatusWriteWorksWithoutAnInstanceId() throws Exception {
        List<String> calls = runAgainstStubAws(script(), "ok", "INSTANCE_ID=\njob_status running\n");

        assertThat(calls).singleElement().asString()
            .contains("SET #status = :s").doesNotContain("instanceId");
    }

    private String jobStatusOutput(String script, String mode) throws Exception {
        Path log = tempDir.resolve("out-" + mode);
        runAgainstStubAws(script, mode, "INSTANCE_ID=i-0abc\njob_status completed > " + log + "\necho after >> " + log + "\n");
        return Files.readString(log);
    }

    /**
     * Runs the script's exports, its {@code job_status} definition and {@code body} under bash,
     * with {@code aws} replaced by a stub that appends its arguments to a file and then succeeds,
     * fails a condition, or fails outright.
     */
    private List<String> runAgainstStubAws(String script, String mode, String body) throws Exception {
        Path calls = tempDir.resolve("calls-" + System.nanoTime());
        Path bin = Files.createDirectories(tempDir.resolve("bin-" + System.nanoTime()));
        Path aws = bin.resolve("aws");
        Files.writeString(aws, """
            #!/usr/bin/env bash
            printf '%s\\n' "$*" >> "$STUB_CALLS"
            case "$STUB_MODE" in
              ok) exit 0 ;;
              conditional) echo "An error occurred (ConditionalCheckFailedException) when calling the UpdateItem operation" >&2; exit 254 ;;
              *) echo "boom" >&2; exit 255 ;;
            esac
            """);
        assertThat(aws.toFile().setExecutable(true)).isTrue();
        String exports = script.lines().filter(l -> l.startsWith("export ")).collect(Collectors.joining("\n", "", "\n"));
        String function = script.substring(script.indexOf("job_status() {"), script.indexOf("\n}\n", script.indexOf("job_status() {")) + 3);

        ProcessBuilder builder = new ProcessBuilder("bash", "-c", exports + function + body)
            .redirectErrorStream(true);
        builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        builder.environment().put("STUB_CALLS", calls.toString());
        builder.environment().put("STUB_MODE", mode);
        Process running = builder.start();
        String output = new String(running.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(running.waitFor(20, TimeUnit.SECONDS)).isTrue();
        assertThat(running.exitValue()).as("bash said: " + output).isZero();
        return Files.exists(calls) ? Files.readAllLines(calls) : List.of();
    }

    // ─── Environment manifest ────────────────────────────────────────────────────

    @Test
    void uploadsTheManifestBeforeStartingTheBenchmark() {
        String script = script();
        int benchmarkStart = script.indexOf("java -jar /app/benchmark-runner.jar");

        assertThat(script.indexOf("${RESULT_PATH}/environment.json"))
            .as("a job that crashes must still leave a record of what it crashed on")
            .isNotNegative()
            .isLessThan(benchmarkStart);
        assertThat(script.indexOf("${RESULT_PATH}/packages.txt"))
            .isNotNegative()
            .isLessThan(benchmarkStart);
    }

    /**
     * The capture sits before the benchmark precisely so a non-zero exit cannot skip it — there
     * is no branch between the two uploads and the {@code timeout java -jar} line.
     */
    @Test
    void manifestUploadIsUnconditional() {
        String script = script();
        String betweenUploadAndBenchmark = script.substring(
            script.indexOf("${RESULT_PATH}/packages.txt"),
            script.indexOf("java -jar /app/benchmark-runner.jar"));

        assertThat(betweenUploadAndBenchmark)
            .as("a conditional here would lose the manifest on exactly the jobs that need it")
            .doesNotContain("exit ")
            .doesNotContain("EXIT_CODE");
    }

    @Test
    void manifestRecordsWhatTheImageCannotControl() {
        assertThat(script())
            .as("instance type and CPU model are properties of the job, not of the image, and "
                + "they move a score further than a JDK patch level does")
            .contains("\"instanceType\": \"${INSTANCE_TYPE}\"")
            .contains("\"cpuModel\": \"${CPU_MODEL}\"");
    }

    @Test
    void manifestCarriesTheImageIdentityAndASchemaVersion() {
        assertThat(script())
            .contains("\"schemaVersion\": ${MANIFEST_SCHEMA_VERSION}")
            .contains("export MANIFEST_SCHEMA_VERSION='" + UserDataScriptBuilder.MANIFEST_SCHEMA_VERSION + "'")
            .contains("export IMAGE_VERSION='1.0.0'")
            .contains("export AMI_ID='ami-0123456789abcdef0'");
    }

    /**
     * The manifest is assembled by shell interpolation, so a stray quote or a missing comma
     * produces a file that only fails when someone runs `baas env diff` weeks later. Substituting
     * representative values and parsing the result catches that here.
     */
    @Test
    void manifestIsValidJsonForARepresentativeCapture() throws Exception {
        String heredoc = script();
        int bodyStart = heredoc.indexOf("{", heredoc.indexOf("cat > /app/environment.json"));
        // The closing delimiter, not the one on the `cat <<MANIFEST` line above it.
        int bodyEnd = heredoc.indexOf("\nMANIFEST\n", bodyStart);
        assertThat(bodyStart).isNotNegative();
        assertThat(bodyEnd).isGreaterThan(bodyStart);
        String manifest = heredoc.substring(bodyStart, bodyEnd);

        assertThat(manifest)
            .as("the body must be plain ${VAR} references — a command substitution here would put "
                + "quotes and parentheses inside a JSON string inside a heredoc")
            .doesNotContain("$(");

        String resolved = manifest
            .replace("${MANIFEST_SCHEMA_VERSION}", String.valueOf(UserDataScriptBuilder.MANIFEST_SCHEMA_VERSION))
            .replaceAll("\\$\\{[A-Z_]+}", "sample-value");

        assertThatCode(() -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = new ObjectMapper().readValue(resolved, Map.class);
            assertThat(parsed)
                .containsKeys("schemaVersion", "imageVersion", "amiId", "instanceType",
                    "cpuModel", "kernelRelease", "jvmVersion", "jvmVendor", "jvmVendorVersion", "jvmName",
                    "perfEventParanoid");
        }).doesNotThrowAnyException();
    }

    /**
     * EC2 instance tags are not result tags. {@code ResultsQueryService} reads
     * {@code benchmarkMetadata.tags}, which is populated only by the runner's own {@code --tag}
     * option — tagging the instance instead leaves every stored result with a null
     * {@code imageVersion}, and filtering or grouping by it silently matches nothing.
     */
    @Test
    void passesEnvironmentTagsToTheRunnerNotJustToTheInstance() {
        String script = script();

        assertThat(script)
            .contains("--tag \"imageVersion=${IMAGE_VERSION_ACTUAL}\"")
            .contains("--tag \"instanceType=${INSTANCE_TYPE}\"");

        assertThat(script.indexOf("--tag \"imageVersion="))
            .as("the tags have to be arguments of the runner invocation itself")
            .isGreaterThan(script.indexOf("java -jar /app/benchmark-runner.jar"))
            .isLessThan(script.indexOf("BENCHMARK_PARAMS_ARRAY[@]"));
    }

    /**
     * The tag values are the ones observed on the instance, the same variables the manifest is
     * built from — so a result's tags cannot disagree with its own environment.json.
     */
    @Test
    void environmentTagsReuseTheObservedValues() {
        String script = script();

        int captured = script.indexOf("IMAGE_VERSION_ACTUAL=$(cat /etc/baas-image-version");
        assertThat(captured).isNotNegative();
        assertThat(script.indexOf("--tag \"imageVersion=${IMAGE_VERSION_ACTUAL}\""))
            .isGreaterThan(captured);

        assertThat(script)
            .as("the manifest reads the same two variables")
            .contains("\"imageVersion\": \"${IMAGE_VERSION_ACTUAL}\"")
            .contains("\"instanceType\": \"${INSTANCE_TYPE}\"");
    }

    // ─── Observed JDK/CPU tags (Task 4) ──────────────────────────────────────────
    //
    // These three tags are captured as shell variables ON the instance, the same rule that
    // already governs imageVersion/instanceType — a result's tags must never be able to disagree
    // with that same job's environment.json. `jdk` is derived from JVM_VERSION_RAW, the SAME
    // single `java -version` observation the manifest's escaped jvmVersion is built from, not a
    // second independent call.

    @Test
    void forwardsObservedEnvironmentTagsToTheRunner() {
        String script = script();

        assertThat(script)
            .contains("--tag \"jdk=${JDK_VERSION}\"")
            .contains("--tag \"cpuModel=${CPU_MODEL_RAW}\"")
            .contains("--tag \"cpuArch=${CPU_ARCH}\"");
    }

    @Test
    void observedTagsAreCapturedBeforeTheyAreUsed() {
        String script = script();

        assertThat(script.indexOf("JDK_VERSION=$("))
            .as("jdk is derived from the raw JVM version observation, not a second java -version call")
            .isGreaterThan(script.indexOf("JVM_VERSION_RAW=$("));
        assertThat(script.indexOf("--tag \"jdk="))
            .isGreaterThan(script.indexOf("JDK_VERSION=$("));
        assertThat(script.indexOf("--tag \"cpuArch="))
            .isGreaterThan(script.indexOf("CPU_ARCH=$("));
        assertThat(script.indexOf("--tag \"cpuModel="))
            .isGreaterThan(script.indexOf("CPU_MODEL_RAW=$("));
    }

    @Test
    void manifestCarriesCpuArchSoTagsCannotDisagreeWithIt() {
        String script = script();

        assertThat(script)
            .as("a tag with no manifest counterpart breaks the observed-values invariant")
            .contains("\"cpuArch\": \"${CPU_ARCH}\"");
    }

    @Test
    void manifestSchemaVersionIsBumpedForTheNewField() {
        assertThat(UserDataScriptBuilder.MANIFEST_SCHEMA_VERSION)
            .as("4 added jvmVendor, jvmVendorVersion and jvmName; 5 renamed requestId to jobId")
            .isEqualTo(5);
    }

    // ─── JVM vendor ──────────────────────────────────────────────────────────────
    //
    // An image extension may replace Corretto with another vendor's build of the same version, and
    // the `java -version` banner names only the version. The vendor is observed once, recorded in
    // the manifest, and tagged from the same variable.

    @Test
    void theManifestRecordsWhoBuiltTheJvm() {
        assertThat(script())
            .contains("\"jvmVendor\": \"${JVM_VENDOR}\"")
            .contains("\"jvmVendorVersion\": \"${JVM_VENDOR_VERSION}\"")
            .contains("\"jvmName\": \"${JVM_NAME}\"")
            .as("a vendor string can contain a double quote or backslash")
            .contains("JVM_VENDOR=$(json_escape \"$JVM_VENDOR_RAW\")");
    }

    @Test
    void theVendorTagIsTheObservedValue() {
        String script = script();

        assertThat(script.indexOf("--tag \"jvmVendor=${JVM_VENDOR_RAW}\""))
            .as("tagged from the same capture the manifest uses, after it is made")
            .isGreaterThan(script.indexOf("JVM_VENDOR_RAW=$("))
            .isGreaterThan(script.indexOf("java -jar /app/benchmark-runner.jar"));
        assertThat(script.split("-XshowSettings:properties", -1))
            .as("one JVM call for every property, not one per field")
            .hasSize(2);
    }

    @Test
    void jvmPropertiesArePickedOutByTheirExactKey() throws Exception {
        String function = script().lines()
            .filter(line -> line.strip().startsWith("jvm_prop()"))
            .findFirst().orElseThrow().strip();
        String props = """
            Property settings:
                java.vendor = Eclipse Adoptium
                java.vendor.url = https://adoptium.net/
                java.vendor.version = Temurin-25.0.4+7
                java.vm.name = OpenJDK 64-Bit Server VM
            openjdk version "25.0.4" 2026-07-15
            """;
        String probe = "JVM_PROPS=$(cat <<'P'\n" + props + "P\n)\n" + function + "\n"
            + "printf '%s|%s|%s' \"$(jvm_prop java.vendor)\" \"$(jvm_prop java.vendor.version)\" \"$(jvm_prop java.vm.name)\"\n";

        var process = new ProcessBuilder("bash", "-c", probe).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

        assertThat(process.waitFor()).isZero();
        assertThat(output)
            .as("java.vendor must not match java.vendor.url or java.vendor.version")
            .isEqualTo("Eclipse Adoptium|Temurin-25.0.4+7|OpenJDK 64-Bit Server VM");
    }

    @Test
    void capturesTheFullPackageListSeparately() {
        assertThat(script())
            .as("rpm -qa is several hundred lines and would drown the manifest's ~20 useful fields")
            .contains("rpm -qa")
            .contains("/app/packages.txt");
    }

    /**
     * `baas run --tag` used to reach the EC2 instance only, so no result from the CLI path
     * carried a caller tag. The whole tag-based query model depends on this reaching the runner.
     */
    @Test
    void forwardsCallerSuppliedTagsToTheRunner() {
        String script = script(Map.of("project", "lynx-journal", "experiment", "gc tuning"));

        assertThat(script)
            .contains("'--tag' 'project=lynx-journal'")
            .contains("'--tag' 'experiment=gc tuning'");

        assertThat(script.indexOf("RUNNER_TAGS_ARRAY[@]"))
            .as("caller tags have to be arguments of the runner invocation itself")
            .isGreaterThan(script.indexOf("java -jar /app/benchmark-runner.jar"))
            .isLessThan(script.indexOf("EXIT_CODE=$?"));
    }

    @Test
    void rendersNoTagArgumentsWhenNoneAreSupplied() {
        String script = script(Map.of());

        assertThat(script)
            .as("an empty array must still be a valid array literal")
            .contains("RUNNER_TAGS_ARRAY=()");
    }

    @Test
    void escapesSingleQuotesInTagValues() {
        String script = script(Map.of("note", "it's fine"));

        assertThat(script)
            .as("each element is single-quoted, so an embedded quote must be escaped")
            .contains("'note=it'\\''s fine'");
    }

    // ─── Fix round 1: a tag value is DATA, never re-parsed as shell ──────────────
    //
    // Tags once went through an export and then an eval — two parses. Since S6 the array literal
    // is written directly, so bash parses each value exactly once. The tests below still run the
    // line through bash and assert on the resulting array, since that is what the runner sees.

    /**
     * CRITICAL (round-1 finding 1): an unescaped {@code $(...)} inside a tag value used to
     * execute during {@code eval}, with RunnerRole's IAM permissions — including SSM read of the
     * MongoDB connection string, a permission the operator policy deliberately withholds. A
     * caller-controlled tag value must never be able to run anything.
     */
    @Test
    void doesNotExecuteCommandSubstitutionOrExpansionInTagValues() throws Exception {
        Path marker = tempDir.resolve("PWNED");
        String payload = "x$(touch " + marker + ")y `id` ${HOME}/z";

        List<String> elements = evaluateRunnerTagsArray(Map.of("note", payload));

        assertThat(Files.exists(marker))
            .as("a tag value must never be re-parsed as shell — command substitution must not run")
            .isFalse();
        assertThat(elements)
            .as("$(...), backticks and ${...} must survive as literal, unexpanded text")
            .containsExactly("--tag", "note=" + payload);
    }

    /**
     * Round-1 finding 2: a double quote in a tag value was silently stripped by eval's second
     * parse instead of reaching {@code benchmarkMetadata.tags} intact.
     */
    @Test
    void preservesDoubleQuotesInTagValues() throws Exception {
        List<String> elements = evaluateRunnerTagsArray(Map.of("note", "say \"hi\""));

        assertThat(elements)
            .as("a double quote in a tag value must reach the runner unstripped")
            .containsExactly("--tag", "note=say \"hi\"");
    }

    @Test
    void preservesBackslashesInTagValues() throws Exception {
        List<String> elements = evaluateRunnerTagsArray(Map.of("path", "C:\\temp\\run"));

        assertThat(elements)
            .containsExactly("--tag", "path=C:\\temp\\run");
    }

    @Test
    void preservesSpacesAsASingleArgvTokenForTagValues() throws Exception {
        List<String> elements = evaluateRunnerTagsArray(Map.of("experiment", "gc tuning"));

        assertThat(elements)
            .as("a value containing a space must still be exactly one argv token")
            .containsExactly("--tag", "experiment=gc tuning");
    }

    @Test
    void preservesSingleQuotesAsLiteralTextInTagValuesWhenActuallyEvaluated() throws Exception {
        List<String> elements = evaluateRunnerTagsArray(Map.of("note", "it's fine"));

        assertThat(elements)
            .containsExactly("--tag", "note=it's fine");
    }

    @Test
    void rendersZeroArrayElementsWhenNoTagsAreSupplied() throws Exception {
        assertThat(evaluateRunnerTagsArray(Map.of())).isEmpty();
    }

    @Test
    void callerTagsPrecedeBenchmarkParameters() {
        String script = script(Map.of("project", "lynx-journal"));

        assertThat(script.indexOf("RUNNER_TAGS_ARRAY[@]"))
            .as("a tag rendered after the params array would be parsed as a JMH argument")
            .isLessThan(script.indexOf("BENCHMARK_PARAMS_ARRAY[@]"));
    }

    /**
     * `source` is caller-overridable, so unlike the observed keys it has no second line of defence
     * in SCRIPT_BODY — the only thing that carries it to the database is the runner's own --tag.
     * Tagging the instance instead is the mistake that leaves every stored result with a null
     * value and no error anywhere.
     */
    @Test
    void theSourceTagReachesTheRunnerAsACommandLineTag() {
        assertThat(script(Map.of("project", "lynx-journal", "source", "ci")))
            .contains("'--tag' 'source=ci'");
    }

    // ─── Final-review I1: defence in depth against a colliding caller tag ────────
    //
    // RunCommand.buildRunnerTags now rejects a caller --tag whose key is machine-observed before
    // the script is ever built. The test below proves a SECOND, independent line of defence: even
    // if a reserved key ever slipped past that guard, SCRIPT_BODY's own ordering must still make
    // the observed value win. The runner parses --tag into a picocli Map<String,String> option
    // (ApiCommonSharedOptions#tags) and duplicate keys are LAST-WINS — verified empirically against
    // picocli 4.7.7 — so this test reproduces the exact argv picocli would see for the runner
    // invocation (in the SAME textual order SCRIPT_BODY emits it) and feeds it through picocli
    // itself, rather than merely asserting on string positions.

    @CommandLine.Command
    static class TagMapProbe {
        @CommandLine.Option(names = "--tag")
        Map<String, String> tags;
    }

    @Test
    void observedTagValueWinsOverACollidingCallerTagUnderPicocliParsing() throws Exception {
        String script = script(Map.of("jdk", "attacker-supplied"));

        assertThat(script)
            .as("the caller tag must actually be rendered into RUNNER_TAGS_ARRAY")
            .contains("'--tag' 'jdk=attacker-supplied'");

        String invocation = script.substring(
            script.indexOf("java -jar /app/benchmark-runner.jar"),
            script.indexOf("EXIT_CODE=$?"));

        // "${RUNNER_TAGS_ARRAY[@]}" expands to the caller's --tag args at THIS position in the
        // invocation; only its position relative to the observed jdk tag line matters, since the
        // array's own content (the literal caller value) lives in the earlier
        // `RUNNER_TAGS_ARRAY=(...)` line, asserted above.
        int callerArrayIndex = invocation.indexOf("RUNNER_TAGS_ARRAY[@]");
        int observedTagIndex = invocation.indexOf("--tag \"jdk=${JDK_VERSION}\"");
        assertThat(callerArrayIndex).as("caller tags array must be expanded in the invocation").isNotNegative();
        assertThat(observedTagIndex).as("observed jdk tag must be rendered in the invocation").isNotNegative();

        // The argv order picocli would actually see is purely a function of SCRIPT_BODY's static
        // text layout — bash array/literal expansion does not reorder arguments — so reproducing
        // that textual order with concrete values reproduces picocli's real parsing outcome.
        List<String> argv = new ArrayList<>();
        if (callerArrayIndex < observedTagIndex) {
            argv.add("--tag"); argv.add("jdk=attacker-supplied");
            argv.add("--tag"); argv.add("jdk=OBSERVED_ON_INSTANCE");
        } else {
            argv.add("--tag"); argv.add("jdk=OBSERVED_ON_INSTANCE");
            argv.add("--tag"); argv.add("jdk=attacker-supplied");
        }

        var probe = new TagMapProbe();
        new CommandLine(probe).parseArgs(argv.toArray(new String[0]));

        assertThat(probe.tags)
            .as("SCRIPT_BODY must render \"${RUNNER_TAGS_ARRAY[@]}\" BEFORE the five observed "
                + "--tag lines, so picocli's last-wins Map parsing keeps the observed value even "
                + "if a reserved key ever slips past RunCommand.buildRunnerTags's own guard")
            .containsEntry("jdk", "OBSERVED_ON_INSTANCE");
    }

    /**
     * environment.json is written before the benchmark, so it is what a job that died early
     * leaves behind — and it is what buys back the self-description the opaque job id gave up.
     */
    @Test
    void theManifestIdentifiesAJobThatStoredNothing() {
        String script = script(Map.of("project", "lynx-journal", "branch", "main"));

        assertThat(script).contains("\"project\": \"${PROJECT}\"")
            .contains("\"branch\": \"${BRANCH}\"")
            .contains("\"jobId\": \"${JOB_ID}\"")
            .contains("\"createdAt\": \"${CREATED_AT}\"");
        assertThat(script).contains("PROJECT=$(json_escape")
            .contains("BRANCH=$(json_escape");
    }

    @Test
    void theRunnerReceivesTheLaunchersInstant() {
        String script = script();

        assertThat(script).contains("export CREATED_AT='2026-07-24T12:00:00Z'");
        assertThat(script).contains("--created-at     \"${CREATED_AT}\"");
    }

    @Test
    void theBenchmarkJarComesFromTheJobsOwnInputPrefix() {
        String script = script();

        assertThat(script).contains(
            "export BENCHMARK_JAR_S3_KEY='jobs/lynx-journal/20260724T120000000Z-a3f9c21b/input/benchmark.jar'");
        assertThat(script).contains(
            "aws s3 cp \"s3://${S3_BUCKET}/${BENCHMARK_JAR_S3_KEY}\" /app/benchmark-under-test.jar");
        assertThat(script).doesNotContain("/jobs/${JOB_ID}/benchmark.jar");
    }

    /**
     * The script is heredoc-heavy and nothing else parses it before cloud-init does, on a paid
     * instance, after the watchdog has already started. A syntax error there costs a job and shows
     * up only in cloud-init-output.log.
     */
    @Test
    void theRenderedScriptIsSyntacticallyValidBash() throws Exception {
        Path scriptFile = tempDir.resolve("user-data.sh");
        Files.writeString(scriptFile, script(Map.of("project", "lynx-journal", "branch", "main")));

        Process process = new ProcessBuilder("bash", "-n", scriptFile.toString())
            .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("bash -n must not hang").isTrue();

        assertThat(process.exitValue()).as("bash -n said: " + output).isZero();
    }

    /**
     * S5: only four of the exports were escaped, and the project reached RESULT_PATH and
     * BENCHMARK_JAR_S3_KEY through two of the others. One stray quote then left bash unable to
     * parse the script at all — before the watchdog, so nothing would terminate the instance.
     * Every export now goes through one helper; this feeds a quote into every value that can carry
     * one and has bash both parse and run the export block.
     */
    @Test
    void everyExportedValueSurvivesAQuoteAsLiteralText() throws Exception {
        Path marker = tempDir.resolve("PWNED");
        String hostile = "x';touch " + marker + ";'";
        String encoded = new UserDataScriptBuilder().build(
            hostile, hostile, hostile, hostile, "jobs/" + hostile + "/id", hostile,
            "jobs/" + hostile + "/id/input/benchmark.jar", 7200, 7500,
            hostile, hostile, hostile, hostile, hostile,
            List.of(hostile), Map.of("project", hostile, "branch", hostile));
        String script = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        String exports = script.lines().filter(l -> l.startsWith("export ")).collect(Collectors.joining("\n", "", "\n"));

        Process process = new ProcessBuilder("bash", "-c",
            exports + "printf '%s\\n' \"$RESULT_PATH\" \"$BENCHMARK_JAR_S3_KEY\" \"$AWS_REGION\"")
            .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();

        assertThat(process.exitValue()).as("bash said: " + output).isZero();
        assertThat(Files.exists(marker)).as("an exported value must never run as shell").isFalse();
        assertThat(output.lines()).containsExactly(
            "jobs/" + hostile + "/id", "jobs/" + hostile + "/id/input/benchmark.jar", hostile);
    }

    /**
     * Review P4: `cmd | head -1 || echo absent` never echoes, since head succeeds on empty input, so
     * a missing tool recorded "" — and asprof, through 2>&1, bash's own error as its version. Runs
     * the script's own capture lines with perf producing nothing and no async-profiler installed.
     */
    @Test
    void aMissingToolIsRecordedAsAbsent() throws Exception {
        String script = script();
        String captures = script.lines()
            .filter(l -> l.startsWith("json_escape()") || l.startsWith("PERF_VERSION=")
                || l.contains("ASYNC_PROFILER_VERSION="))
            .collect(Collectors.joining("\n", "", "\n"));
        assertThat(captures).contains("/app/async-profiler/bin/asprof");
        assertThat(Path.of("/app/async-profiler/bin/asprof")).doesNotExist();

        // ASYNC_PROFILER_VERSION set in the environment, as CI's own workflow does: a capture that
        // only assigns when asprof exists would otherwise report the inherited value, not "absent".
        var builder = new ProcessBuilder("bash", "-c",
            "perf() { :; }\n" + captures + "printf '%s\\n' \"$PERF_VERSION\" \"$ASYNC_PROFILER_VERSION\"")
            .redirectErrorStream(true);
        builder.environment().put("ASYNC_PROFILER_VERSION", "inherited-from-the-environment");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();

        assertThat(output.lines()).containsExactly("absent", "absent");
    }

    /**
     * Review P13: the manifest's async-profiler capture names the binary by path, a third copy of
     * the declared {@code installPath}. Changing the declaration without it would quietly record
     * "absent" on an image that has async-profiler — so every reference must follow the declaration.
     */
    @Test
    void theAsyncProfilerCaptureFollowsTheDeclaredInstallPath() {
        String asprof = new RunnerImageRenderer().definition().tools().asyncProfiler().installPath() + "/bin/asprof";
        String script = script();

        int references = script.split("/bin/asprof", -1).length - 1;
        int declared = script.split(java.util.regex.Pattern.quote(asprof), -1).length - 1;
        assertThat(references).as("the capture names asprof at all").isPositive();
        assertThat(declared)
            .as("every asprof reference in user-data must use runner-image.yaml's installPath (" + asprof + ")")
            .isEqualTo(references);
    }

    /**
     * private-runner-network would otherwise have to tunnel this egress through a NAT or a VPC
     * endpoint, and releases/latest was an unpinned drift axis besides.
     */
    @Test
    void theInstanceNeverContactsGitHubForItsRunner() {
        String script = script();

        assertThat(script).doesNotContain("api.github.com")
            .doesNotContain("releases/latest")
            .doesNotContain("wget");
        // Exactly one runner download, and it is the bucket copy. (The JAR path itself appears
        // twice — the copy, then `java -jar` — so the download line is what must be unique.)
        assertThat(script).containsOnlyOnce(
            "aws s3 cp \"s3://${S3_BUCKET}/${RUNNER_JAR_S3_KEY}\" /app/benchmark-runner.jar");
    }
}
