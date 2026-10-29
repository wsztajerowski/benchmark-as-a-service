package pl.wsztajerowski.baas.console;

import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * How log lines look, decided once in {@code main} before any logger exists. On a terminal a line is
 * {@code WARN <message>}, its level in Maven's colours; anywhere else it keeps the full format from
 * {@code simplelogger.properties} — timestamp, level, logger name — and no escape sequence.
 *
 * <p>Short needs stdout to be a terminal by {@link Console}'s definition <em>and</em> stderr to be
 * that same terminal. Java answers the first and not the second, and the first alone is wrong for
 * {@code baas run … 2> run.log}: a log file without timestamps. Whether {@code /dev/fd/2} is the
 * same file as {@code /dev/fd/1} answers the second; anything that cannot be answered — no
 * {@code /dev/fd}, an exception — counts as no, so every misdetection is today's full format.
 */
public final class LogStyle {

    private LogStyle() {}

    static final String SHOW_DATE_TIME = "org.slf4j.simpleLogger.showDateTime";
    static final String SHOW_SHORT_LOG_NAME = "org.slf4j.simpleLogger.showShortLogName";

    enum Mode { FULL, SHORT, SHORT_COLOURED }

    /** For the real process. Must run before the first logger is created. */
    public static void apply() {
        var console = System.console();
        Mode mode = decide(console != null && console.isTerminal(), stderrIsStdout(), System.getenv());
        if (mode == Mode.FULL) {
            return;
        }
        System.setProperty(SHOW_DATE_TIME, "false");
        System.setProperty(SHOW_SHORT_LOG_NAME, "false");
        if (mode == Mode.SHORT_COLOURED) {
            System.setErr(colouring(System.err));
        }
    }

    static Mode decide(boolean terminal, boolean stderrIsStdout, Map<String, String> environment) {
        Console console = Console.of(new PrintWriter(OutputStream.nullOutputStream()), terminal, environment);
        if (!console.interactive() || !stderrIsStdout) {
            return Mode.FULL;
        }
        return console.colour() ? Mode.SHORT_COLOURED : Mode.SHORT;
    }

    private static boolean stderrIsStdout() {
        try {
            return Files.isSameFile(Path.of("/dev/fd/1"), Path.of("/dev/fd/2"));
        } catch (Exception e) {
            return false;
        }
    }

    static PrintStream colouring(PrintStream target) {
        Charset charset = target.charset();
        return new PrintStream(new LevelColouring(target, charset), true, charset);
    }
}
