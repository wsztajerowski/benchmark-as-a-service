package pl.wsztajerowski.baas.console;

import java.io.OutputStream;
import java.io.PrintStream;

/**
 * One line at the bottom of an interactive terminal, redrawn in place.
 *
 * <p>Anything else written while it is shown — a log line on stderr, a payload line through the
 * {@link Console} — would land on the same screen row and tear it. So while open, the line
 * replaces {@code System.err} with a stream that clears the line before each fresh output line and
 * redraws it after, and the {@code Console} routes its own writes through {@link #interleave}. The
 * result is log output scrolling above a line that stays put. SimpleLogger resolves
 * {@code System.err} on every write, which is what makes the replacement reach it.
 *
 * <p>Every method is synchronized: the shutdown hook closes the line from another thread while
 * the poll loop may be redrawing it. {@link #close()} is idempotent, and nothing draws after it.
 * Text is kept short by the caller rather than fitted to the terminal width, so a redraw never
 * wraps.
 */
public final class StatusLine implements AutoCloseable {

    private static final String CLEAR = "\r\u001b[K";

    private final Console console;
    private final PrintStream originalErr;
    private String text = "";
    private boolean closed;
    private boolean errAtLineStart = true;

    StatusLine(Console console) {
        this.console = console;
        this.originalErr = System.err;
        // Same charset as the stream it stands in for: the wrapper encodes, the original only
        // receives bytes, so a mismatch would garble every non-ASCII log line.
        System.setErr(new PrintStream(new InterleavingErr(), true, originalErr.charset()));
    }

    public synchronized void update(String text) {
        if (closed) {
            return;
        }
        this.text = text;
        draw();
    }

    /** Clears the line, restores {@code System.err}, and leaves the cursor at the start of a free line. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        console.raw(CLEAR);
        System.setErr(originalErr);
        console.statusLineClosed();
    }

    /** Clear, write, and redraw once the write has finished a line. */
    synchronized void interleave(Runnable write, boolean endsLine) {
        if (!closed) {
            console.raw(CLEAR);
        }
        write.run();
        if (!closed && endsLine) {
            draw();
        }
    }

    private void draw() {
        if (!text.isEmpty()) {
            console.raw(CLEAR + text);
        }
    }

    /**
     * Clears only at the start of a line and redraws only at its end: {@code PrintStream.println}
     * reaches here as the text and the line separator in separate writes, and redrawing between
     * them would glue the status text onto the log line.
     */
    private final class InterleavingErr extends OutputStream {
        @Override
        public void write(int b) {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            if (length == 0) {
                return;
            }
            synchronized (StatusLine.this) {
                if (!closed && errAtLineStart) {
                    console.raw(CLEAR);
                }
                originalErr.write(bytes, offset, length);
                originalErr.flush();
                errAtLineStart = bytes[offset + length - 1] == '\n';
                if (!closed && errAtLineStart) {
                    draw();
                }
            }
        }

        @Override
        public void flush() {
            originalErr.flush();
        }
    }
}
