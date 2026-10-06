package pl.wsztajerowski.baas.commands;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.model.JobItem;
import pl.wsztajerowski.baas.model.JobStatus;
import pl.wsztajerowski.baas.jobs.JobListing;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JobsCommandTest {

    private static final Instant NOW = Instant.parse("2026-10-03T00:12:04Z");

    private static JobListing.Row row(String id, String status, String errorCode) {
        var job = new JobItem(id, "lynx-journal", Instant.parse("2026-10-03T00:00:00Z"),
            "jobs/lynx-journal/" + id, "c6i.4xlarge", status, "i-0def", errorCode,
            Map.of("source", "ci", "exclude_from_results", "true"), null);
        return new JobListing.Row(job, status, JobStatus.isInFlight(status) ? "i-0def" : null);
    }

    private static String render(JobsListSubcommand list, List<JobListing.Row> rows) {
        var out = new StringWriter();
        list.console = Console.plain(new PrintWriter(out, true));
        list.print(rows, NOW);
        return out.toString();
    }

    private static JobsListSubcommand list(String... args) {
        var list = new JobsListSubcommand();
        new CommandLine(list).parseArgs(args);
        return list;
    }

    @Test
    void theBareGroupPrintsUsageNamingBothSubcommands() {
        var out = new StringWriter();
        var commandLine = new CommandLine(new JobsCommand());
        commandLine.setOut(new PrintWriter(out, true));

        assertThat(commandLine.execute()).isZero();
        assertThat(out.toString()).contains("list").contains("terminate");
    }

    /** Removed outright, with no alias: `runs` sat one letter from the `run` verb. */
    @Test
    void theOldRunsGroupNoLongerParses() {
        var root = new CommandLine(new pl.wsztajerowski.baas.BaasApp());

        assertThat(root.getSubcommands()).containsKey("jobs").doesNotContainKey("runs");
        assertThat(root.execute("runs", "list")).isNotZero();
    }

    @Test
    void theDefaultIsTwentyJobsOfAnyStatus() {
        var list = list();

        assertThat(list.limit).isEqualTo(20);
        assertThat(list.inFlight).isFalse();
    }

    @Test
    void theTableShowsSourceElapsedAndAFailedLaunchsReason() {
        String table = render(list(), List.of(
            row("20261003T000000000Z-a3f9c21b", JobStatus.RUNNING, null),
            row("20261003T000000000Z-0c11aa02", JobStatus.LAUNCH_FAILED, "InsufficientInstanceCapacity")));

        assertThat(table)
            .contains("JOB_ID", "STATUS", "SOURCE", "ELAPSED")
            .contains("20261003T000000000Z-a3f9c21b", "running", "ci", "12m 04s")
            .contains("launch-failed", "InsufficientInstanceCapacity");
    }

    @Test
    void anEmptyInFlightListingSaysSo() {
        assertThat(render(list("--in-flight"), List.of())).isEqualTo("No jobs in flight." + System.lineSeparator());
        assertThat(render(list(), List.of())).isEqualTo("No jobs found." + System.lineSeparator());
    }

    @Test
    void jsonCarriesBothStatusesAndTheTags() {
        String json = render(list("--format", "json"), List.of(row("r1", JobStatus.RUNNING, null)));

        assertThat(json).startsWith("[").contains("\"jobId\":\"r1\"", "\"status\":\"running\"",
            "\"storedStatus\":\"running\"", "\"errorCode\":null", "\"exclude_from_results\":\"true\"");
    }

    @Test
    void csvHasAHeaderAndOneLinePerJob() {
        String csv = render(list("--format", "csv"), List.of(row("r1", JobStatus.COMPLETED, null)));

        assertThat(csv.lines().toList()).hasSize(2);
        assertThat(csv.lines().findFirst().orElseThrow()).startsWith("jobId,project,status");
    }

    @Test
    void terminateRefusesWithoutATerminalOrYes() {
        var terminate = new JobsTerminateSubcommand();
        new CommandLine(terminate).parseArgs("r1");
        terminate.console = Console.plain(new PrintWriter(new StringWriter(), true));

        assertThat(terminate.confirmation().getAsBoolean()).isFalse();
    }

    @Test
    void terminateWithYesNeedsNoPrompt() {
        var terminate = new JobsTerminateSubcommand();
        new CommandLine(terminate).parseArgs("r1", "--yes");
        terminate.answerReader = () -> { throw new AssertionError("must not prompt"); };

        assertThat(terminate.confirmation().getAsBoolean()).isTrue();
    }

    @Test
    void terminatePromptsOnATerminal() {
        var terminate = new JobsTerminateSubcommand();
        new CommandLine(terminate).parseArgs("r1");
        terminate.console = Console.withFlags(new PrintWriter(new StringWriter(), true), true, false);
        terminate.answerReader = () -> "y";

        assertThat(terminate.confirmation().getAsBoolean()).isTrue();
    }

    @Test
    void theDefaultPageIsTheTwentyNewestAndTheCutIsAnnounced() {
        var rows = new java.util.ArrayList<JobListing.Row>();
        for (int day = 1; day <= 30; day++) {
            var job = new JobItem("job-" + day, "p", Instant.parse("2026-09-%02dT00:00:00Z".formatted(day)),
                "jobs/p/job-" + day, "c5.2xlarge", JobStatus.COMPLETED, null, null, Map.of(), null);
            rows.add(new JobListing.Row(job, JobStatus.COMPLETED, null));
        }
        var notes = new java.util.ArrayList<String>();

        var page = list().page(rows, notes::add);

        assertThat(page).hasSize(20).first().extracting(r -> r.job().jobId()).isEqualTo("job-30");
        assertThat(notes).singleElement().asString().contains("20 of 30");
        assertThat(list("--offset", "20").page(rows, notes::add)).hasSize(10);
        assertThat(list("--limit", "0").page(rows, notes::add)).hasSize(30);
    }

    @Test
    void watchingWithoutATerminalIsRefused() throws Exception {
        var err = new StringWriter();
        int exit = new CommandLine(new pl.wsztajerowski.baas.BaasApp())
            .setErr(new PrintWriter(err, true))
            .execute("jobs", "list", "--watch");

        assertThat(exit).isEqualTo(2);
    }
}
