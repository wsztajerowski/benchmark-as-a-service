package pl.wsztajerowski.baas.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class UserDataScriptBuilderTest {

    private String script() {
        return script(Map.of());
    }

    private String script(Map<String, String> runnerTags) {
        String encoded = new UserDataScriptBuilder().build(
            "eu-central-1", "baas-a1b2c3d4", "a1b2c3d4", "jmh",
            "jmh-20260724_120000", "main/jmh/20260724_120000", 7200, 7500,
            "1.0.0", "ami-0123456789abcdef0", null, List.of("MyBenchmark", "-f", "1"),
            runnerTags);
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
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

        int watchdogStart = script.indexOf("# Layer 1: background watchdog");
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
     * paid instance. Errors are handled by exit code and the run-status sentinel instead.
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
     * /proc entries that disappear mid-walk, which fails the run after the benchmark has
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
        assertThat(script()).contains("export BENCHMARK_PARAMETERS='MyBenchmark -f 1'");
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

    // ─── Environment manifest ────────────────────────────────────────────────────

    @Test
    void uploadsTheManifestBeforeStartingTheBenchmark() {
        String script = script();
        int benchmarkStart = script.indexOf("java -jar /app/benchmark-runner.jar");

        assertThat(script.indexOf("${RESULT_PATH}/environment.json"))
            .as("a run that crashes must still leave a record of what it crashed on")
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
            .as("a conditional here would lose the manifest on exactly the runs that need it")
            .doesNotContain("exit ")
            .doesNotContain("EXIT_CODE");
    }

    @Test
    void manifestRecordsWhatTheImageCannotControl() {
        assertThat(script())
            .as("instance type and CPU model are properties of the run, not of the image, and "
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
            .replace("${MANIFEST_SCHEMA_VERSION}", "1")
            .replaceAll("\\$\\{[A-Z_]+}", "sample-value");

        assertThatCode(() -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = new ObjectMapper().readValue(resolved, Map.class);
            assertThat(parsed)
                .containsKeys("schemaVersion", "imageVersion", "amiId", "instanceType",
                    "cpuModel", "kernelRelease", "jvmVersion", "perfEventParanoid");
        }).doesNotThrowAnyException();
    }

    /**
     * EC2 instance tags are not result tags. {@code ResultsQueryService} reads
     * {@code benchmarkMetadata.tags}, which is populated only by the runner's own {@code --tag}
     * option — tagging the instance instead leaves every stored result with a null
     * {@code imageVersion}, and the tier-1 comparison silently never fires.
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
            .contains("--tag \"project=lynx-journal\"")
            .contains("--tag \"experiment=gc tuning\"");

        assertThat(script.indexOf("RUNNER_TAGS_ARRAY[@]"))
            .as("caller tags have to be arguments of the runner invocation itself")
            .isGreaterThan(script.indexOf("java -jar /app/benchmark-runner.jar"))
            .isLessThan(script.indexOf("EXIT_CODE=$?"));
    }

    @Test
    void rendersNoTagArgumentsWhenNoneAreSupplied() {
        String script = script(Map.of());

        assertThat(script)
            .as("an empty array must still expand cleanly under the eval pattern")
            .contains("export RUNNER_TAGS=''");
    }

    @Test
    void escapesSingleQuotesInTagValues() {
        String script = script(Map.of("note", "it's fine"));

        assertThat(script)
            .as("the export is single-quoted, so an embedded quote must be escaped")
            .contains("--tag \"note=it'\\''s fine\"");
    }
}
