package pl.wsztajerowski.baas.console;

import java.io.PrintWriter;
import java.util.Locale;
import java.util.Map;

/**
 * The one path every command payload takes to standard output. Diagnostics do not come here —
 * they go to the logger, on stderr.
 *
 * <p>Wraps picocli's {@code getOut()}, which buffers and flushes only on {@code println}/
 * {@code printf}. Every method here flushes, so a same-line prompt and a {@code \r} redraw reach
 * the terminal when written, and a stray {@code System.out} write elsewhere is the only way left
 * to get output out of order.
 *
 * <p>Terminal effects — colour, redraw — are decided once, at construction, and only ever fail
 * towards plain output: see {@link #of(PrintWriter, boolean, Map)}.
 */
public final class Console {

    private static final String ESC = "\u001b[";

    private final PrintWriter out;
    private final boolean interactive;
    private final boolean colour;
    private StatusLine statusLine;

    Console(PrintWriter out, boolean interactive, boolean colour) {
        this.out = out;
        this.interactive = interactive;
        this.colour = colour;
    }

    /** The console for a real process: flags derived from the JVM's console and environment. */
    public static Console of(PrintWriter out) {
        var console = System.console();
        return of(out, console != null && console.isTerminal(), System.getenv());
    }

    /**
     * Interactive means stdin and stdout are both a terminal ({@code System.console()} answers for
     * the pair, passed in as {@code terminal}) and {@code TERM} is not {@code dumb}. The
     * {@code isTerminal()} half of {@code terminal} guards against a JDK whose console provider
     * returns a {@code Console} for a redirected stream.
     *
     * <p>Every wrong answer this gives is a <em>no</em> — stdin redirected on a real terminal — so
     * a misdetection costs polish and never writes an escape sequence into a file or pipe. It
     * cannot see stderr, which is why the status line lives on stdout. picocli's
     * {@code Ansi.AUTO} is deliberately not used: it colours under {@code TERM=dumb}, and
     * {@code CLICOLOR_FORCE} makes it colour a pipe.
     */
    static Console of(PrintWriter out, boolean terminal, Map<String, String> environment) {
        boolean interactive = terminal && !"dumb".equals(environment.get("TERM"));
        boolean colour = interactive && environment.get("NO_COLOR") == null;
        return new Console(out, interactive, colour);
    }

    /** No colour, no redraw — for tests and for any caller that must stay plain. */
    public static Console plain(PrintWriter out) {
        return new Console(out, false, false);
    }

    /** Explicit flags, for tests that exercise the interactive paths without a terminal. */
    public static Console withFlags(PrintWriter out, boolean interactive, boolean colour) {
        return new Console(out, interactive, colour);
    }

    public boolean interactive() {
        return interactive;
    }

    public void print(String text) {
        write(text);
    }

    public void println(String text) {
        write(text + System.lineSeparator());
    }

    /** {@link Locale#ROOT}, always: a comma-decimal locale would corrupt JSON and CSV numbers. */
    public void printf(String format, Object... args) {
        write(String.format(Locale.ROOT, format, args));
    }

    /** {@code text} in bold, or unchanged when colour is off. */
    public String bold(String text) {
        return sgr("1", text);
    }

    /** {@code text} de-emphasised, for values that are unknown rather than measured. */
    public String faint(String text) {
        return sgr("2", text);
    }

    public String red(String text) {
        return sgr("31", text);
    }

    public String green(String text) {
        return sgr("32", text);
    }

    /** Clears the screen and homes the cursor. Only meaningful, and only allowed, when interactive. */
    public void clearScreen() {
        if (!interactive) {
            throw new IllegalStateException("clearScreen on a non-interactive console");
        }
        write(ESC + "H" + ESC + "2J");
    }

    /**
     * A status line on this console, or {@code null} when not interactive — the caller then keeps
     * reporting progress the way it did before, through the logger.
     */
    public StatusLine openStatusLine() {
        if (!interactive) {
            return null;
        }
        synchronized (this) {
            statusLine = new StatusLine(this);
            return statusLine;
        }
    }

    synchronized void statusLineClosed() {
        statusLine = null;
    }

    private void write(String text) {
        StatusLine line;
        synchronized (this) {
            line = statusLine;
        }
        if (line != null) {
            line.interleave(() -> raw(text), text.endsWith("\n"));
        } else {
            raw(text);
        }
    }

    /** Writes and flushes, bypassing the status line. Only {@link StatusLine} calls this directly. */
    void raw(String text) {
        out.print(text);
        out.flush();
    }

    private String sgr(String code, String text) {
        return colour ? ESC + code + "m" + text + ESC + "0m" : text;
    }
}
