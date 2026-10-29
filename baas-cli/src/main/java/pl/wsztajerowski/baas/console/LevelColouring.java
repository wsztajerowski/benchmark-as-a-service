package pl.wsztajerowski.baas.console;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.Map;

/**
 * Colours the level word that starts a log line — Maven's colours: {@code ERROR} bold red,
 * {@code WARN} bold yellow, {@code INFO} bold blue, {@code DEBUG} and {@code TRACE} bold cyan — and
 * passes everything else through unchanged. A line is held until its newline so its first word can
 * be read; a flush before then writes what is held — coloured if the level word is already whole —
 * so a same-line prompt still appears, and the rest of that line follows uncoloured.
 * Installed as {@code System.err} only when the log style is short and colour is allowed.
 */
final class LevelColouring extends OutputStream {

    private static final Map<String, String> COLOURS = Map.of(
        "ERROR", "1;31", "WARN", "1;33", "INFO", "1;34", "DEBUG", "1;36", "TRACE", "1;36");

    private final PrintStream target;
    private final Charset charset;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    /** False once part of the current line has been flushed: its start is already written. */
    private boolean atLineStart = true;

    LevelColouring(PrintStream target, Charset charset) {
        this.target = target;
        this.charset = charset;
    }

    @Override
    public synchronized void write(int b) {
        line.write(b);
        if (b == '\n') {
            emit();
            atLineStart = true;
        }
    }

    @Override
    public synchronized void flush() {
        if (line.size() > 0) {
            emit();
            atLineStart = false;
        }
        target.flush();
    }

    private void emit() {
        String text = line.toString(charset);
        line.reset();
        target.print(atLineStart ? coloured(text) : text);
    }

    static String coloured(String text) {
        int space = text.indexOf(' ');
        if (space <= 0) {
            return text;
        }
        String colour = COLOURS.get(text.substring(0, space));
        return colour == null ? text : "\u001b[" + colour + "m" + text.substring(0, space) + "\u001b[0m" + text.substring(space);
    }
}
