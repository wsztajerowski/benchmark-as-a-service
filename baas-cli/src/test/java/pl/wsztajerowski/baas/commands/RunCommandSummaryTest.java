package pl.wsztajerowski.baas.commands;

import com.fasterxml.jackson.databind.JsonNode;
import pl.wsztajerowski.baas.BaasApp;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --format json} exists because the run id reaches only {@code logger.info} → stderr with a
 * timestamp prefix, so a continuous-integration job cannot correlate a run it launched.
 *
 * <p>The success path cannot be driven without provisioning an instance — CLAUDE.md records that
 * {@code RunCommand.call()} is executed by no test — so the object's shape is pinned against
 * {@link RunCommand#printRunSummary} directly, and the wiring that prints it on failure is driven
 * through {@code call()} on a path that fails before any AWS call.
 */
class RunCommandSummaryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private record Captured(String out, String err, int exitCode) {}

    private static Captured run(String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            // Driven through the real command tree, not a bare RunCommand: LoggingMixin's -v
            // setter casts mixee.root() to BaasApp, so a standalone RunCommand fails to parse -v
            // before the command ever runs.
            var app = new BaasApp();
            int code = new CommandLine(app).execute(args);
            return new Captured(out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8), code);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private static String printSummary(RunCommand command, int exitCode) {
        var out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            command.printRunSummary(exitCode);
        } finally {
            System.setOut(original);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void theSummaryCarriesEveryFieldACallerNeedsToCorrelateARun() throws Exception {
        var command = new RunCommand();
        command.format = "json";
        command.summaryRunId = "20260820T174432812Z-a3f9c21b";
        command.summaryProject = "lynx-journal";
        command.summaryResultPath = "runs/lynx-journal/20260820T174432812Z-a3f9c21b/";
        command.summaryInstanceId = "i-0123456789abcdef0";

        JsonNode parsed = JSON.readTree(printSummary(command, 0));

        assertThat(parsed.get("runId").asText()).isEqualTo("20260820T174432812Z-a3f9c21b");
        assertThat(parsed.get("project").asText()).isEqualTo("lynx-journal");
        assertThat(parsed.get("resultPath").asText())
            .isEqualTo("runs/lynx-journal/20260820T174432812Z-a3f9c21b/");
        assertThat(parsed.get("status").asText()).isEqualTo("completed");
        assertThat(parsed.get("exitCode").asInt()).isZero();
        assertThat(parsed.get("instanceId").asText()).isEqualTo("i-0123456789abcdef0");
    }

    /**
     * The failure case is precisely when the id is needed — to {@code baas download} it and surface
     * {@code cloud-init-output.log}, the documented place to start when a run dies before producing
     * output. So the object is printed, and the command still exits non-zero.
     */
    @Test
    void aFailedRunStillReportsItsIdentifierAndExitsNonZero() throws Exception {
        var command = new RunCommand();
        command.format = "json";
        command.summaryRunId = "20260820T174432812Z-a3f9c21b";
        command.summaryProject = "lynx-journal";
        command.summaryResultPath = "runs/lynx-journal/20260820T174432812Z-a3f9c21b/";
        command.summaryInstanceId = "i-0123456789abcdef0";

        JsonNode parsed = JSON.readTree(printSummary(command, 1));

        assertThat(parsed.get("status").asText()).isEqualTo("failed");
        assertThat(parsed.get("exitCode").asInt()).isOne();
        assertThat(parsed.get("runId").asText()).isEqualTo("20260820T174432812Z-a3f9c21b");
    }

    @Test
    void aRunThatFailsBeforeLaunchingStillWritesOneParseableObject() throws Exception {
        var captured = run("run", "--format", "json", "--benchmark-jar", "/nonexistent.jar", "not-a-type");

        assertThat(captured.exitCode()).isNotZero();
        JsonNode parsed = JSON.readTree(captured.out().strip());
        assertThat(parsed.get("status").asText()).isEqualTo("failed");
        assertThat(parsed.get("exitCode").asInt()).isNotZero();
        assertThat(parsed.get("runId").isNull())
            .as("no run was named, and a placeholder would be indistinguishable from a real id")
            .isTrue();
    }

    /**
     * The rule CLAUDE.md states for {@code ResultsCommand.printJson}: payload on standard output,
     * diagnostics on the logger. A timestamp prefix on the payload line breaks {@code | jq}, and
     * {@code -v} is exactly when a diagnostic is most likely to land on the wrong stream.
     *
     * <p>Scope, deliberately stated: this drives {@code -v} through the real command tree, but
     * debug-level output cannot be observed in-process. {@code BaasApp} raises the level from its
     * execution strategy and from {@code main}'s argv pre-scan, and SimpleLogger pins a logger's
     * level when the logger is constructed — {@code RunCommand}'s is {@code static final} and is
     * already loaded by the time any test runs. So what this pins is the stream split that holds
     * at every level: the ERROR diagnostic goes to standard error and standard output carries the
     * object alone. The debug-level half is verified out-of-process against the built JAR.
     */
    @Test
    void verboseDiagnosticsDoNotCorruptTheObject() throws Exception {
        var captured = run("run", "-v", "--format", "json", "--benchmark-jar", "/nonexistent.jar", "not-a-type");

        String out = captured.out().strip();
        assertThat(out.lines()).as("standard output must hold the object alone").hasSize(1);
        assertThat(out).doesNotContain("ERROR").doesNotContain("INFO");
        assertThat(JSON.readTree(out).get("status").asText()).isEqualTo("failed");
        assertThat(captured.err())
            .as("the diagnostic itself must still be reported, on standard error")
            .contains("not-a-type");
    }

    /**
     * The defect a real CI run found. {@code showResults} prints the post-run table through
     * {@code ResultsQueryService.printTable}, which writes to {@code System.out} — correctly, as a
     * command payload. But the JSON summary is a payload on that same stream, so under
     * {@code --format json} the table landed first and {@code | jq} failed on the opening token.
     * The workflow read an empty run id and went on to query {@code --request-id ""}.
     *
     * <p>The earlier redirect test missed this because it drove a path that fails before launching,
     * which never reaches {@code showResults} at all.
     */
    @Test
    void theResultTableIsSuppressedUnderJsonSoStandardOutputHoldsTheObjectAlone() throws Exception {
        var command = new RunCommand();
        command.format = "json";
        command.summaryRunId = "20260920T161636923Z-08785de7";
        command.summaryProject = "benchmark-as-a-service";
        command.summaryResultPath = "runs/benchmark-as-a-service/20260920T161636923Z-08785de7/";
        command.summaryInstanceId = "i-03c3ad558f45b388c";

        var printed = new java.util.concurrent.atomic.AtomicBoolean(false);
        var out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            command.reportRunResults(java.util.List.of(), "20260920T161636923Z-08785de7",
                rows -> printed.set(true));
            command.printRunSummary(0);
        } finally {
            System.setOut(original);
        }

        assertThat(printed).as("the table must not be printed when the object owns stdout").isFalse();
        String captured = out.toString(StandardCharsets.UTF_8).strip();
        assertThat(captured.lines()).hasSize(1);
        assertThat(JSON.readTree(captured).get("runId").asText())
            .isEqualTo("20260920T161636923Z-08785de7");
    }

    /** Default output is unchanged: the table is still the point of a run you watch. */
    @Test
    void theResultTableStillPrintsWithoutTheOption() {
        var command = new RunCommand();
        var printed = new java.util.concurrent.atomic.AtomicBoolean(false);

        command.reportRunResults(java.util.List.of(), "20260920T161636923Z-08785de7",
            rows -> printed.set(true));

        assertThat(printed).isTrue();
    }

    @Test
    void withoutTheOptionNoObjectIsWritten() {
        var captured = run("run", "--benchmark-jar", "/nonexistent.jar", "not-a-type");

        assertThat(captured.exitCode()).isNotZero();
        assertThat(captured.out())
            .as("default output is unchanged — the summary is opt-in")
            .doesNotContain("\"runId\"");
        assertThat(captured.err()).contains("not-a-type");
    }
}
