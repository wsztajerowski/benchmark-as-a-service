package pl.wsztajerowski.baas.results;

import picocli.CommandLine;
import pl.wsztajerowski.baas.BaasApp;
import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.console.Console;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import java.lang.reflect.Method;

/**
 * How {@code baas results query} uses the {@code Console}: machine formats stay plain on any
 * terminal, and {@code --watch} refuses what it cannot redraw and redraws on the alternate screen.
 */
class ResultsConsoleOutputTest {

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

    private static ResultRow row(String jobId, String imageVersion) {
        return new ResultRow(jobId, "com.example.B.run", "jmh", "thrpt", 1.0, 0.1, "ops/s",
            "2026-08-20T17:44:32.812Z", Map.of("imageVersion", imageVersion, "instanceType", "c5.2xlarge"));
    }

    @Test
    void jsonAndCsvStayPlainEvenWhenColourIsOn() throws Exception {
        for (String method : List.of("printJson", "printCsv")) {
            var out = new StringWriter();
            var command = new ResultsQuerySubcommand();
            command.console = Console.withFlags(new PrintWriter(out), true, true);
            Method print = ResultsQuerySubcommand.class.getDeclaredMethod(method, List.class);
            print.setAccessible(true);
            print.invoke(command, List.of(row("r1", "1.0.0")));

            assertThat(out.toString()).as(method).isNotEmpty().doesNotContain(ESC);
        }
    }

    // --- --watch ---------------------------------------------------------------------------

    /** Surefire has no console, so this is the redirected case: refused before any config or AWS. */
    @Test
    void watchIsRefusedWithoutATerminal() {
        var captured = run("query", "--watch", "--project", "p");

        assertThat(captured.exitCode()).isEqualTo(2);
        assertThat(captured.err()).contains("--watch needs an interactive terminal");
        assertThat(captured.out()).isEmpty();
    }

    @Test
    void watchIsRefusedForAMachineFormat() {
        var captured = run("query", "--watch", "--format", "json", "--project", "p");

        assertThat(captured.exitCode()).isEqualTo(2);
        assertThat(captured.err()).contains("--watch applies to the table only");
        assertThat(captured.out()).isEmpty();
    }

    @Test
    void aWatchFrameCarriesRowWarningsBelowTheTableInsteadOfLoggingThem() {
        var out = new StringWriter();
        var err = new ByteArrayOutputStream();
        var command = new ResultsQuerySubcommand();
        command.console = Console.withFlags(new PrintWriter(out), true, false);
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            var rows = List.of(row("r1", "1.0.0"), row("r2", "1.1.0"));
            command.printFrame(rows, List.of("No row carries tag 'experiment'"), "12:00:00");
        } finally {
            System.setErr(originalErr);
        }

        String frame = out.toString();
        assertThat(frame).startsWith(ESC + "[H" + ESC + "[2J");
        assertThat(frame).contains("refreshed 12:00:00", "Ctrl+C to stop");
        assertThat(frame.indexOf("No row carries tag")).isGreaterThan(frame.indexOf("r2"));
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
        var command = new ResultsQuerySubcommand();
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
}
