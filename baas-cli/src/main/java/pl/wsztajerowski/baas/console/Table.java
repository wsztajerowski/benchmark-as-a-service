package pl.wsztajerowski.baas.console;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * A fixed-width table on a {@link Console}: pad first, colour second.
 *
 * <p>Each cell is padded to its column width in plain text and only then styled, so escape
 * sequences never count towards a width and a coloured table lines up exactly like a plain one.
 * Truncation is the caller's: a value longer than its column overflows, as {@code %-Ns} did.
 *
 * @param ruleWidth length of the dashed rule under the header — given, not derived, so each table
 *                  keeps the rule it always printed
 */
public record Table(Console console, int ruleWidth, List<Column> columns) {

    /** One column; {@code width} as in {@code %-Ns}, or {@code %Ns} when {@code rightAligned}. */
    public record Column(String header, int width, boolean rightAligned) {
        public static Column left(String header, int width) {
            return new Column(header, width, false);
        }

        public static Column right(String header, int width) {
            return new Column(header, width, true);
        }
    }

    /** A cell value and the style applied to it after padding. */
    public record Cell(String text, UnaryOperator<String> style) {
        public static Cell plain(String text) {
            return new Cell(text, UnaryOperator.identity());
        }
    }

    public void printHeader() {
        var cells = columns.stream().map(c -> new Cell(c.header(), console::bold)).toList();
        printRow(cells);
        console.println("-".repeat(ruleWidth));
    }

    public void printRow(String... values) {
        printRow(java.util.Arrays.stream(values).map(Cell::plain).toList());
    }

    public void printRow(List<Cell> cells) {
        if (cells.size() != columns.size()) {
            throw new IllegalArgumentException(
                "expected " + columns.size() + " cells, got " + cells.size());
        }
        var line = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                line.append(' ');
            }
            line.append(pad(columns.get(i), cells.get(i)));
        }
        console.println(line.toString());
    }

    private static String pad(Column column, Cell cell) {
        String text = cell.text() != null ? cell.text() : "null";
        String padding = " ".repeat(Math.max(0, column.width() - text.length()));
        String styled = cell.style().apply(text);
        return column.rightAligned() ? padding + styled : styled + padding;
    }
}
