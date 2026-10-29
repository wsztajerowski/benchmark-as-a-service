package pl.wsztajerowski.baas.console;

/**
 * Text written by a runner, made safe to print: C0 and C1 control characters and DEL are removed,
 * tab is kept. A runner can write tags, job items and S3 objects (S7, X3), so an escape sequence in
 * one could otherwise rewrite what the operator sees, set the window title or reach the clipboard.
 * Applied to values, never to text {@link Console} has already styled.
 */
public final class Printable {

    private Printable() {}

    public static String of(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder kept = new StringBuilder(text.length());
        text.codePoints()
            .filter(c -> c == '\t' || !(c < 0x20 || (c >= 0x7F && c <= 0x9F)))
            .forEach(kept::appendCodePoint);
        return kept.toString();
    }
}
