package pl.wsztajerowski.baas.console;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * {@code --watch} on a listing: redraw a frame in place every {@link #INTERVAL}, on the alternate
 * screen, until interrupted. Shared by {@code results query} and {@code jobs list} so the two behave
 * alike — refused without a terminal or with a machine format, and leaving the last frame on the
 * normal screen when it ends.
 */
public final class Watch {

    public static final Duration INTERVAL = Duration.ofSeconds(30);

    private final Console console;
    /** The frame on screen, reprinted on the normal screen when the watch ends. */
    private String lastFrame;
    private boolean onAlternateScreen;

    public Watch(Console console) {
        this.console = console;
    }

    /**
     * Refused rather than degraded: without a terminal there is nothing to redraw, and a pipe
     * receiving a new table every 30 s forever is not output anyone asked for.
     */
    public static String refusal(String format, Console console) {
        if (!"table".equals(format.toLowerCase(Locale.ROOT))) {
            return "--watch applies to the table only; it cannot be combined with --format " + format + ".";
        }
        if (!console.interactive()) {
            return "--watch needs an interactive terminal; standard output is redirected or piped.";
        }
        return null;
    }

    /**
     * Runs until interrupted (Ctrl+C). Leaving restores the normal screen and reprints the last
     * frame there, so the result outlives the command — in a {@code finally} for a failed refresh,
     * whose error must be logged on the normal screen, and in a shutdown hook for Ctrl+C.
     * {@link #leave} is idempotent, so both firing is harmless.
     *
     * @param body renders one frame's content onto the console it is given
     */
    public void run(Consumer<Console> body) throws InterruptedException {
        var time = DateTimeFormatter.ofPattern("HH:mm:ss");
        enter();
        Runtime.getRuntime().addShutdownHook(new Thread(this::leave));
        try {
            while (true) {
                show(body, LocalTime.now().format(time));
                Thread.sleep(INTERVAL.toMillis());
            }
        } finally {
            leave();
        }
    }

    public synchronized void enter() {
        console.enterAlternateScreen();
        onAlternateScreen = true;
    }

    public synchronized void leave() {
        if (!onAlternateScreen) {
            return;
        }
        onAlternateScreen = false;
        console.leaveAlternateScreen();
        if (lastFrame != null) {
            console.print(lastFrame);
        }
    }

    /**
     * One frame: a header, then the body. Rendered to a string first, so the same text can be
     * reprinted on exit.
     */
    public synchronized void show(Consumer<Console> body, String refreshedAt) {
        var frame = new StringWriter();
        var out = console.renderingTo(new PrintWriter(frame));
        out.println(out.faint("Every " + INTERVAL.toSeconds() + "s · refreshed " + refreshedAt
            + " · Ctrl+C to stop"));
        out.println("");
        body.accept(out);
        lastFrame = frame.toString();
        console.showFrame(lastFrame);
    }
}
