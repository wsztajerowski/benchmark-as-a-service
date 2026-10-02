package pl.wsztajerowski.baas.commands;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.model.RunItem;
import pl.wsztajerowski.baas.model.RunStatus;
import pl.wsztajerowski.baas.runs.RunListing;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RunsCommandTest {

    private static final Instant NOW = Instant.parse("2026-10-03T00:12:04Z");

    private static RunListing.Row row(String id, String status, String errorCode) {
        var run = new RunItem(id, "lynx-journal", Instant.parse("2026-10-03T00:00:00Z"),
            "runs/lynx-journal/" + id, "c6i.4xlarge", status, "i-0def", errorCode,
            Map.of("source", "ci", "exclude_from_results", "true"), null);
        return new RunListing.Row(run, status, RunStatus.isInFlight(status) ? "i-0def" : null);
    }

    private static String render(RunsListSubcommand list, List<RunListing.Row> rows) {
        var out = new StringWriter();
        list.console = Console.plain(new PrintWriter(out, true));
        list.print(rows, NOW);
        return out.toString();
    }

    private static RunsListSubcommand list(String... args) {
        var list = new RunsListSubcommand();
        new CommandLine(list).parseArgs(args);
        return list;
    }

    @Test
    void theBareGroupPrintsUsageNamingBothSubcommands() {
        var out = new StringWriter();
        var commandLine = new CommandLine(new RunsCommand());
        commandLine.setOut(new PrintWriter(out, true));

        assertThat(commandLine.execute()).isZero();
        assertThat(out.toString()).contains("list").contains("terminate");
    }

    @Test
    void theDefaultIsTwentyRunsOfAnyStatus() {
        var list = list();

        assertThat(list.limit).isEqualTo(20);
        assertThat(list.inFlight).isFalse();
    }

    @Test
    void theTableShowsSourceElapsedAndAFailedLaunchsReason() {
        String table = render(list(), List.of(
            row("20261003T000000000Z-a3f9c21b", RunStatus.RUNNING, null),
            row("20261003T000000000Z-0c11aa02", RunStatus.LAUNCH_FAILED, "InsufficientInstanceCapacity")));

        assertThat(table)
            .contains("RUN_ID", "STATUS", "SOURCE", "ELAPSED")
            .contains("20261003T000000000Z-a3f9c21b", "running", "ci", "12m 04s")
            .contains("launch-failed", "InsufficientInstanceCapacity");
    }

    @Test
    void anEmptyInFlightListingSaysSo() {
        assertThat(render(list("--in-flight"), List.of())).isEqualTo("No runs in flight." + System.lineSeparator());
        assertThat(render(list(), List.of())).isEqualTo("No runs found." + System.lineSeparator());
    }

    @Test
    void jsonCarriesBothStatusesAndTheTags() {
        String json = render(list("--format", "json"), List.of(row("r1", RunStatus.RUNNING, null)));

        assertThat(json).startsWith("[").contains("\"runId\":\"r1\"", "\"status\":\"running\"",
            "\"storedStatus\":\"running\"", "\"errorCode\":null", "\"exclude_from_results\":\"true\"");
    }

    @Test
    void csvHasAHeaderAndOneLinePerRun() {
        String csv = render(list("--format", "csv"), List.of(row("r1", RunStatus.COMPLETED, null)));

        assertThat(csv.lines().toList()).hasSize(2);
        assertThat(csv.lines().findFirst().orElseThrow()).startsWith("runId,project,status");
    }

    @Test
    void terminateRefusesWithoutATerminalOrYes() {
        var terminate = new RunsTerminateSubcommand();
        new CommandLine(terminate).parseArgs("r1");
        terminate.console = Console.plain(new PrintWriter(new StringWriter(), true));

        assertThat(terminate.confirmation().getAsBoolean()).isFalse();
    }

    @Test
    void terminateWithYesNeedsNoPrompt() {
        var terminate = new RunsTerminateSubcommand();
        new CommandLine(terminate).parseArgs("r1", "--yes");
        terminate.answerReader = () -> { throw new AssertionError("must not prompt"); };

        assertThat(terminate.confirmation().getAsBoolean()).isTrue();
    }

    @Test
    void terminatePromptsOnATerminal() {
        var terminate = new RunsTerminateSubcommand();
        new CommandLine(terminate).parseArgs("r1");
        terminate.console = Console.withFlags(new PrintWriter(new StringWriter(), true), true, false);
        terminate.answerReader = () -> "y";

        assertThat(terminate.confirmation().getAsBoolean()).isTrue();
    }
}
