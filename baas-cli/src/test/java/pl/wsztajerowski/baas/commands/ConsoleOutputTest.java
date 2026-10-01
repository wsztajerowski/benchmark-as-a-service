package pl.wsztajerowski.baas.commands;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import pl.wsztajerowski.baas.BaasApp;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.results.EnvironmentManifest.Difference;
import pl.wsztajerowski.baas.results.ResultRow;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the commands use the {@code Console}: machine formats stay plain on any terminal, the
 * env-diff table colours without moving, {@code --watch} refuses what it cannot redraw, and the
 * status line's text stays on one row.
 */
class ConsoleOutputTest {

    private static final String ESC = "\u001b";

    private record Captured(String out, String err, int exitCode) {}

    private static Captured run(String... args) {
        var out = new StringWriter();
        var err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int code = new CommandLine(new BaasApp()).setOut(new PrintWriter(out)).execute(args);
            return new Captured(out.toString(), err.toString(StandardCharsets.UTF_8), code);
        } finally {
            System.setErr(originalErr);
        }
    }

    private static ResultRow row(String requestId, String imageVersion) {
        return new ResultRow(requestId, "com.example.B.run", "jmh", "thrpt", 1.0, 0.1, "ops/s",
            "2026-08-20T17:44:32.812Z", Map.of("imageVersion", imageVersion, "instanceType", "c5.2xlarge"));
    }

    // --- machine formats -------------------------------------------------------------------

    @Test
    void jsonAndCsvStayPlainEvenWhenColourIsOn() throws Exception {
        for (String method : List.of("printJson", "printCsv")) {
            var out = new StringWriter();
            var command = new ResultsCommand();
            command.console = Console.withFlags(new PrintWriter(out), true, true);
            Method print = ResultsCommand.class.getDeclaredMethod(method, List.class);
            print.setAccessible(true);
            print.invoke(command, List.of(row("r1", "1.0.0")));

            assertThat(out.toString()).as(method).isNotEmpty().doesNotContain(ESC);
        }
    }

    @Test
    void theRunSummaryStaysPlainEvenWhenColourIsOn() {
        var out = new StringWriter();
        var command = new RunCommand();
        command.format = "json";
        command.console = Console.withFlags(new PrintWriter(out), true, true);

        command.printRunSummary(0);

        assertThat(out.toString()).startsWith("{").doesNotContain(ESC);
    }

    /** The deployer policy goes through println, which never styles: pasteable as IAM JSON. */
    @Test
    void printlnNeverStyles() {
        var out = new StringWriter();
        Console.withFlags(new PrintWriter(out), true, true).println("{\"Version\":\"2012-10-17\"}");

        assertThat(out.toString()).doesNotContain(ESC);
    }

    // --- env diff --------------------------------------------------------------------------

    private static String diff(boolean colour) {
        var command = new EnvDiffSubcommand();
        command.resultPathA = "runs/p/20260724T120000000Z-a3f9c21b";
        command.resultPathB = "runs/p/20260811T093000000Z-b7e4d0f2";
        Map<String, Difference> differences = new LinkedHashMap<>();
        differences.put("jdk", new Difference("25.0.3", "25.0.4"));
        differences.put("perf", new Difference("", "6.1"));
        var out = new StringWriter();
        command.printDiff(Console.withFlags(new PrintWriter(out), colour, colour), differences);
        return out.toString();
    }

    @Test
    void thePlainDiffIsByteIdenticalToTheOldPrintf() {
        String fmt = "%-24s %-34s %-34s%n";
        String expected = String.format(fmt, "FIELD", "…ns/p/20260724T120000000Z-a3f9c21b", "…ns/p/20260811T093000000Z-b7e4d0f2")
            + "-".repeat(94) + System.lineSeparator()
            + String.format(fmt, "jdk", "25.0.3", "25.0.4")
            + String.format(fmt, "perf", "(absent)", "6.1");

        assertThat(diff(false)).isEqualTo(expected);
    }

    @Test
    void theColouredDiffDistinguishesTheSidesWithoutMovingThem() {
        String coloured = diff(true);

        assertThat(coloured).contains(ESC + "[31m25.0.3").contains(ESC + "[32m25.0.4");
        assertThat(coloured.replaceAll(ESC + "\\[[0-9;]*m", "")).isEqualTo(diff(false));
    }

    // --- --watch ---------------------------------------------------------------------------

    /** Surefire has no console, so this is the redirected case: refused before any config or AWS. */
    @Test
    void watchIsRefusedWithoutATerminal() {
        var captured = run("results", "--watch", "--project", "p");

        assertThat(captured.exitCode()).isEqualTo(2);
        assertThat(captured.err()).contains("--watch needs an interactive terminal");
        assertThat(captured.out()).isEmpty();
    }

    @Test
    void watchIsRefusedForAMachineFormat() {
        var captured = run("results", "--watch", "--format", "json", "--project", "p");

        assertThat(captured.exitCode()).isEqualTo(2);
        assertThat(captured.err()).contains("--watch applies to the table only");
        assertThat(captured.out()).isEmpty();
    }

    @Test
    void aWatchFrameCarriesRowWarningsBelowTheTableInsteadOfLoggingThem() {
        var out = new StringWriter();
        var err = new ByteArrayOutputStream();
        var command = new ResultsCommand();
        command.console = Console.withFlags(new PrintWriter(out), true, false);
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            var rows = List.of(row("r1", "1.0.0"), row("r2", "1.1.0"));
            command.printFrame(rows, List.of("These rows span runner image versions: 1.0.0, 1.1.0"), "12:00:00");
        } finally {
            System.setErr(originalErr);
        }

        String frame = out.toString();
        assertThat(frame).startsWith(ESC + "[H" + ESC + "[2J");
        assertThat(frame).contains("refreshed 12:00:00", "Ctrl+C to stop");
        assertThat(frame.indexOf("span runner image versions")).isGreaterThan(frame.indexOf("r2"));
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    /**
     * The defect a real terminal showed: clearing the normal screen scrolled it, and each frame was
     * drawn at the bottom of a screen-high gap. Frames now go to the alternate screen, and leaving
     * it reprints the last frame on the normal one — once, however many times leave is called.
     */
    @Test
    void watchDrawsOnTheAlternateScreenAndLeavesTheLastFrameBehind() {
        var out = new StringWriter();
        var command = new ResultsCommand();
        command.console = Console.withFlags(new PrintWriter(out), true, false);

        command.enterWatch();
        command.printFrame(List.of(row("r1", "1.0.0")), List.of(), "12:00:00");
        command.printFrame(List.of(row("r2", "1.0.0")), List.of(), "12:00:30");
        command.leaveWatch();
        command.leaveWatch();

        String screen = out.toString();
        String leave = ESC + "[?1049l";
        assertThat(screen).startsWith(ESC + "[?1049h" + ESC + "[H" + ESC + "[2J");
        assertThat(screen.split(java.util.regex.Pattern.quote(leave), -1)).hasSize(2);
        String afterLeave = screen.substring(screen.indexOf(leave) + leave.length());
        assertThat(afterLeave).contains("refreshed 12:00:30", "r2").doesNotContain("r1").doesNotContain(ESC + "[2J");
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
    void theStatusTextFitsEightyColumnsForAMaximalRun() {
        String text = RunCommand.statusText("shutting-down", 99 * 3600 + 59 * 60 + 59, "i-0123456789abcdef0");

        assertThat(text).isEqualTo("shutting-down · 99h 59m 59s elapsed · i-0123456789abcdef0");
        assertThat(text.length()).isLessThan(80);
    }

    @Test
    void elapsedTimeReadsAsMinutesUnderAnHour() {
        assertThat(RunCommand.formatElapsed(75)).isEqualTo("1m 15s");
        assertThat(RunCommand.formatElapsed(3600)).isEqualTo("1h 00m 00s");
    }
}
