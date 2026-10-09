package pl.wsztajerowski.baas.results;

import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.console.Table;
import pl.wsztajerowski.baas.console.Table.Cell;
import pl.wsztajerowski.baas.console.Table.Column;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * The {@code baas results query} table, also printed by {@code baas run} after a job. Command payload,
 * so it goes to the {@link Console} rather than the logger — see {@code ResultsQuerySubcommand#printJson}.
 */
public final class ResultsTable {

    // 28 is JobId.LENGTH. Truncating at 17 landed inside the old <type>-<date> prefix, which
    // rendered distinct rows identically; a fixed-width id removes truncation as a question.
    // No ±ERROR column: the table is for reading scores side by side, and the error stays in JSON
    // and CSV, where something can compute with it.
    private static final List<Column> COLUMNS = List.of(
        Column.left("BENCHMARK", 45),
        Column.left("JOB_ID", 28),
        Column.left("TYPE", 14),
        Column.left("MODE", 8),
        Column.right("SCORE", 14),
        Column.left("UNIT", 10));

    private static final Column PROJECT = Column.left("PROJECT", 24);

    private static final int RULE_WIDTH = 128;

    private ResultsTable() {
    }

    /** One project's rows, no tag lines — what {@code baas run} prints after a job. */
    public static void print(Console console, List<ResultRow> rows) {
        print(console, rows, false, false);
    }

    /**
     * @param withProject a leading PROJECT column, for {@code --all-projects}
     * @param withTags    indented lines under each row, for {@code -v}: the row's params, then every
     *                    tag. Outside the column grid, so however many there are, no column moves
     */
    public static void print(Console console, List<ResultRow> rows, boolean withProject, boolean withTags) {
        if (rows.isEmpty()) {
            console.println("No results found.");
            return;
        }
        var columns = new ArrayList<Column>();
        if (withProject) {
            columns.add(PROJECT);
        }
        columns.addAll(COLUMNS);
        var table = new Table(console, RULE_WIDTH + (withProject ? PROJECT.width() + 1 : 0), columns);
        table.printHeader();
        for (ResultRow r : rows) {
            String shortName = r.benchmarkName().contains(".")
                ? r.benchmarkName().substring(r.benchmarkName().lastIndexOf('.') + 1)
                : r.benchmarkName();
            // An excluded row is shown only under --show-excluded, and faint so it reads as set aside.
            UnaryOperator<String> rowStyle = r.excluded() ? console::faint : UnaryOperator.identity();
            var cells = new ArrayList<Cell>();
            if (withProject) {
                cells.add(new Cell(truncate(r.project(), 23), rowStyle));
            }
            cells.add(new Cell(truncate(shortName, 44), rowStyle));
            cells.add(new Cell(r.jobId(), rowStyle));
            cells.add(new Cell(truncate(r.benchmarkType(), 13), rowStyle));
            cells.add(new Cell(r.mode() != null ? r.mode() : "", rowStyle));
            cells.add(number(console, r.score(), rowStyle));
            cells.add(new Cell(r.scoreUnit() != null ? r.scoreUnit() : "", rowStyle));
            table.printRow(cells);
            if (withTags && !r.params().isEmpty()) {
                console.println("    " + console.faint("params  " + tagLine(r.params())));
            }
            if (withTags && !r.tags().isEmpty()) {
                console.println("    " + console.faint("tags    " + tagLine(r.tags())));
            }
        }
    }

    /** Sorted by key so two rows' lines can be compared by eye. Used for params and tags alike. */
    static String tagLine(Map<String, String> tags) {
        return new TreeMap<>(tags).entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining("  "));
    }

    /** An unknown value is de-emphasised, so it cannot be read as a measurement. */
    private static Cell number(Console console, double value, UnaryOperator<String> rowStyle) {
        String text = String.format("%.3f", value);
        return Double.isFinite(value) ? new Cell(text, rowStyle) : new Cell(text, console::faint);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
