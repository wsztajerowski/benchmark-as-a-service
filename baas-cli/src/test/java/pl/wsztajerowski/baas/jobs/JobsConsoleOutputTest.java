package pl.wsztajerowski.baas.jobs;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.jobs.EnvironmentManifest.Difference;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How {@code baas run} and {@code baas jobs diff} use the {@code Console}: the job summary stays
 * plain, the env-diff table colours without moving, and the status line appears only on a terminal
 * and its text stays on one row.
 */
class JobsConsoleOutputTest {

    private static final String ESC = "\u001b";

    @Test
    void theJobSummaryStaysPlainEvenWhenColourIsOn() {
        var out = new StringWriter();
        var command = new RunCommand();
        command.format = "json";
        command.console = Console.withFlags(new PrintWriter(out), true, true);

        command.printJobSummary(0);

        assertThat(out.toString()).startsWith("{").doesNotContain(ESC);
    }

    // --- jobs diff -------------------------------------------------------------------------

    private static String diff(boolean colour, PackagesDiff packages) {
        var command = new JobsDiffSubcommand();
        command.jobA = "20260724T120000000Z-a3f9c21b";
        command.jobB = "20260811T093000000Z-b7e4d0f2";
        Map<String, Map<String, Difference>> differences = new LinkedHashMap<>();
        Map<String, Difference> jvm = new LinkedHashMap<>();
        jvm.put("version", new Difference("25.0.3", "25.0.4"));
        differences.put("jvm", jvm);
        differences.put("tools", Map.of("perf", new Difference("", "6.1")));
        var out = new StringWriter();
        command.printDiff(Console.withFlags(new PrintWriter(out), colour, colour), differences, packages);
        return out.toString();
    }

    private static String diff(boolean colour) {
        return diff(colour, null);
    }

    @Test
    void thePlainDiffNamesTheGroupsThenListsTheFields() {
        String fmt = "%-10s %-18s %-30s %-30s%n";
        String expected = "Differs in: jvm, tools" + System.lineSeparator()
            + System.lineSeparator()
            + String.format(fmt, "GROUP", "FIELD", "20260724T120000000Z-a3f9c21b", "20260811T093000000Z-b7e4d0f2")
            + "-".repeat(91) + System.lineSeparator()
            + String.format(fmt, "jvm", "version", "25.0.3", "25.0.4")
            + String.format(fmt, "tools", "perf", "(absent)", "6.1");

        assertThat(diff(false)).isEqualTo(expected);
    }

    @Test
    void theColouredDiffDistinguishesTheSidesWithoutMovingThem() {
        String coloured = diff(true);

        assertThat(coloured).contains(ESC + "[31m25.0.3").contains(ESC + "[32m25.0.4");
        assertThat(coloured.replaceAll(ESC + "\\[[0-9;]*m", "")).isEqualTo(diff(false));
    }

    @Test
    void identicalEnvironmentsSaySo() {
        var command = new JobsDiffSubcommand();
        var out = new StringWriter();
        command.printDiff(Console.plain(new PrintWriter(out, true)), Map.of(), null);

        assertThat(out.toString()).startsWith("No differences. Both jobs measured on the same environment.");
    }

    @Test
    void packagesAreAGroupWhenTheAmisDiffer() {
        var packages = PackagesDiff.of("openssl-libs-3.0.8-1.amzn2023.0.14.x86_64\n",
            "openssl-libs-3.0.8-1.amzn2023.0.16.x86_64\nzstd-libs-1.5.5-1.amzn2023.0.2.x86_64\n");

        String out = diff(false, packages);

        assertThat(out).startsWith("Differs in: jvm, tools, packages")
            .contains("Packages (AMIs differ): 1 changed · 1 added · 0 removed")
            .contains("changed  openssl-libs  3.0.8-1.amzn2023.0.14 → 3.0.8-1.amzn2023.0.16")
            .contains("added    zstd-libs 1.5.5-1.amzn2023.0.2");
    }

    // --- status line gating ----------------------------------------------------------------

    /** CI's path: no terminal, so no status line — the poll loop keeps its "Still running" log line. */
    @Test
    void noStatusLineWithoutATerminal() {
        var command = new RunCommand();
        command.console = Console.plain(new PrintWriter(new StringWriter()));

        assertThat(command.openStatusLine()).isNull();
    }

    /** Under --format json standard output belongs to the summary object, terminal or not. */
    @Test
    void noStatusLineUnderJsonEvenOnATerminal() {
        var command = new RunCommand();
        command.format = "json";
        command.console = Console.withFlags(new PrintWriter(new StringWriter()), true, true);

        assertThat(command.openStatusLine()).isNull();
    }

    @Test
    void aStatusLineOnATerminalWithoutJson() {
        var command = new RunCommand();
        command.console = Console.withFlags(new PrintWriter(new StringWriter()), true, true);
        PrintStream originalErr = System.err;

        try (var line = command.openStatusLine()) {
            assertThat(line).isNotNull();
        }
        assertThat(System.err).isSameAs(originalErr);
    }

    // --- status line text ------------------------------------------------------------------

    @Test
    void theStatusTextFitsEightyColumnsForAMaximalJob() {
        String text = JobLaunch.statusText("shutting-down", 99 * 3600 + 59 * 60 + 59, "i-0123456789abcdef0");

        assertThat(text).isEqualTo("shutting-down · 99h 59m 59s elapsed · i-0123456789abcdef0");
        assertThat(text.length()).isLessThan(80);
    }
}
