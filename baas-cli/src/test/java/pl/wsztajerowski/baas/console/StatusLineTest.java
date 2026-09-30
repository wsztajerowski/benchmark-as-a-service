package pl.wsztajerowski.baas.console;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The status line shares a screen with the logger. What matters is the order of bytes on the
 * terminal: stdout and stderr are captured separately here, so the test asserts each stream's
 * sequence and that {@code System.err} is the real one again afterwards.
 */
class StatusLineTest {

    private static final String CLEAR = "\r\u001b[K";

    private final StringWriter out = new StringWriter();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream originalErr;
    private PrintStream capturedErr;

    @BeforeEach
    void captureErr() {
        originalErr = System.err;
        capturedErr = new PrintStream(err, true, StandardCharsets.UTF_8);
        System.setErr(capturedErr);
    }

    @AfterEach
    void restoreErr() {
        System.setErr(originalErr);
    }

    private Console interactive() {
        return Console.withFlags(new PrintWriter(out), true, true);
    }

    @Test
    void updatesRedrawInPlace() {
        try (var line = interactive().openStatusLine()) {
            line.update("running · 0m 15s elapsed · i-1");
            line.update("running · 0m 30s elapsed · i-1");
        }

        assertThat(out.toString()).isEqualTo(
            CLEAR + "running · 0m 15s elapsed · i-1"
                + CLEAR + "running · 0m 30s elapsed · i-1"
                + CLEAR);
    }

    /** A log line clears the status line first and redraws it once the log line is complete. */
    @Test
    void aLogLineScrollsAboveTheStatusLine() {
        try (var line = interactive().openStatusLine()) {
            line.update("running");
            System.err.println("WARN something");

            assertThat(out.toString()).isEqualTo(CLEAR + "running" + CLEAR + CLEAR + "running");
        }
        assertThat(err.toString(StandardCharsets.UTF_8)).isEqualTo("WARN something" + System.lineSeparator());
    }

    /** println arrives as text and separator in two writes; a redraw between them would glue lines. */
    @Test
    void aSplitLogWriteRedrawsOnlyAtTheEndOfTheLine() {
        try (var line = interactive().openStatusLine()) {
            line.update("s");
            System.err.print("part one ");
            System.err.print("part two");
            System.err.print(System.lineSeparator());

            assertThat(out.toString()).isEqualTo(CLEAR + "s" + CLEAR + CLEAR + "s");
        }
    }

    @Test
    void consolePayloadIsInterleavedTheSameWay() {
        var console = interactive();
        try (var line = console.openStatusLine()) {
            line.update("s");
            console.println("row");
        }

        String sep = System.lineSeparator();
        assertThat(out.toString()).isEqualTo(CLEAR + "s" + CLEAR + "row" + sep + CLEAR + "s" + CLEAR);
    }

    @Test
    void closingRestoresStandardErrorAndIsIdempotent() {
        var line = interactive().openStatusLine();
        assertThat(System.err).isNotSameAs(capturedErr);

        line.close();
        line.close();
        line.update("ignored after close");

        assertThat(System.err).isSameAs(capturedErr);
        assertThat(out.toString()).isEqualTo(CLEAR);
    }

    /** Once closed, the console writes straight through again — nothing is cleared or redrawn. */
    @Test
    void afterCloseTheConsoleWritesPlainly() {
        var console = interactive();
        var line = console.openStatusLine();
        line.update("s");
        line.close();
        out.getBuffer().setLength(0);

        console.println("after");

        assertThat(out.toString()).isEqualTo("after" + System.lineSeparator());
    }
}
