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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class UserDataScriptBuilderTest {

    @TempDir
    Path tempDir;

    private String script() {
        return script(Map.of());
    }

    private String script(Map<String, String> runnerTags) {
        return script(runnerTags, "baas-a1b2c3d4-results", false);
    }

    private String script(Map<String, String> runnerTags, String resultsTable, boolean noDatabase) {
        String encoded = new UserDataScriptBuilder().build(
            "eu-central-1", "baas-a1b2c3d4", "jmh",
            "jmh-20260724_120000", "main/jmh/20260724_120000", 7200, 7500,
            "1.0.0", "ami-0123456789abcdef0", null, resultsTable, noDatabase,
            List.of("MyBenchmark", "-f", "1"), runnerTags);
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    /**
     * Actually runs the generated {@code export RUNNER_TAGS=...} line through bash, then the
     * script's own {@code eval "RUNNER_TAGS_ARRAY=(${RUNNER_TAGS})"} line — the exact two-parse
     * sequence the real user-data script performs — and returns the resulting array elements.
     *
     * A substring check on the rendered script text cannot catch either of the round-1 defects:
     * the export line is always "correct" by construction (it's just Java string concatenation),
     * and the bug only exists in what bash's SECOND parse (the eval) does with that text. Only
     * executing it proves the fix.
     */
    private List<String> evaluateRunnerTagsArray(Map<String, String> runnerTags) throws Exception {
        String script = script(runnerTags);
        String exportLine = script.lines()
            .filter(l -> l.startsWith("export RUNNER_TAGS="))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no RUNNER_TAGS export line in generated script"));

        String harness = exportLine + "\n"
            + "eval \"RUNNER_TAGS_ARRAY=(${RUNNER_TAGS})\"\n"
            + "for element in \"${RUNNER_TAGS_ARRAY[@]}\"; do printf '%s\\n' \"$element\"; done\n";

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
        assertThat(script).contains("STORE_ARGS=(--results-table \"${RESULTS_TABLE}\")");
        assertThat(script).contains("\"${STORE_ARGS[@]}\"");
    }

    /**
     * Not cosmetic: while this fetch existed the runner picked up MONGO_CONNECTION_STRING from
     * the environment and wrote to Atlas, so a leftover line here would send measurements to a
     * store the CLI can no longer read — and the run would still report success.
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
     * Both branches are always present in the script text — which one runs is decided at boot by
     * these two exports, so they are what the CLI's choice actually reduces to. That the right
     * branch is taken is asserted by executing it, below.
     */
    @Test
    void selectsTheNoOpStoreOnlyWhenNoDatabaseIsAskedFor() {
        assertThat(script()).contains("export NO_DATABASE='false'");

        String discarding = script(Map.of(), null, true);
        assertThat(discarding).contains("export NO_DATABASE='true'");
        assertThat(discarding).contains("export RESULTS_TABLE=''");
    }

    /**
     * The runner selects its adapter from exactly one of these, and rejects both-or-neither. The
     * script decides at boot which single argument it passes, so the two can never arrive
     * together however the CLI is invoked.
     */
    @Test
    void passesExactlyOneStoreSelectionArgument() throws Exception {
        for (boolean noDatabase : new boolean[]{false, true}) {
            String script = script(Map.of(), noDatabase ? null : "baas-a1b2c3d4-results", noDatabase);
            String harness = script.lines()
                .filter(l -> l.startsWith("export RESULTS_TABLE=") || l.startsWith("export NO_DATABASE="))
                .collect(java.util.stream.Collectors.joining("\n"))
                + "\n"
                + script.substring(script.indexOf("if [[ \"${NO_DATABASE}\""),
                    script.indexOf("# Layer 2"))
                + "for element in \"${STORE_ARGS[@]}\"; do printf '%s\\n' \"$element\"; done\n";

            Process process = new ProcessBuilder("bash", "-c", harness).start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();

            assertThat(stdout.lines().toList())
                .as("--no-database=" + noDatabase)
                .isEqualTo(noDatabase
                    ? List.of("--no-database")
                    : List.of("--results-table", "baas-a1b2c3d4-results"));
        }
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
            .replace("${MANIFEST_SCHEMA_VERSION}", String.valueOf(UserDataScriptBuilder.MANIFEST_SCHEMA_VERSION))
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

    // ─── Observed JDK/CPU tags (Task 4) ──────────────────────────────────────────
    //
    // These three tags are captured as shell variables ON the instance, the same rule that
    // already governs imageVersion/instanceType — a result's tags must never be able to disagree
    // with that same run's environment.json. `jdk` is derived from JVM_VERSION_RAW, the SAME
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
        assertThat(UserDataScriptBuilder.MANIFEST_SCHEMA_VERSION).isEqualTo(2);
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

    // ─── Fix round 1: a tag value is DATA, never re-parsed as shell ──────────────
    //
    // RUNNER_TAGS is exported once (parse 1: bash's own single-quote handling) and then handed to
    // `eval "RUNNER_TAGS_ARRAY=(${RUNNER_TAGS})"` (parse 2: eval re-parses the expanded text as
    // shell source). A substring check on the rendered script text cannot see what parse 2 does —
    // the export line is "correct" by construction regardless of what eval later makes of it. The
    // tests below actually run both parses through bash and assert on the resulting array.

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
            .as("the caller tag must actually be rendered into RUNNER_TAGS")
            .contains("--tag \"jdk=attacker-supplied\"");

        String invocation = script.substring(
            script.indexOf("java -jar /app/benchmark-runner.jar"),
            script.indexOf("EXIT_CODE=$?"));

        // "${RUNNER_TAGS_ARRAY[@]}" expands to the caller's --tag args at THIS position in the
        // invocation; only its position relative to the observed jdk tag line matters, since the
        // array's own content (the literal caller value) lives in the earlier `export
        // RUNNER_TAGS=...` line, asserted above.
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
}
