package pl.wsztajerowski.baas.results;

import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.console.Table;
import pl.wsztajerowski.baas.console.Table.Cell;
import pl.wsztajerowski.baas.console.Table.Column;

import java.util.List;

/**
 * The {@code baas results} table, also printed by {@code baas run} after a run. Command payload,
 * so it goes to the {@link Console} rather than the logger — see {@code ResultsCommand#printJson}.
 */
public final class ResultsTable {

    // 28 is RunId.LENGTH. Truncating at 17 landed inside the old <type>-<date> prefix, which
    // rendered distinct rows identically; a fixed-width id removes truncation as a question.
    private static final List<Column> COLUMNS = List.of(
        Column.left("BENCHMARK", 45),
        Column.left("REQUEST_ID", 28),
        Column.left("TYPE", 14),
        Column.left("MODE", 8),
        Column.right("SCORE", 14),
        Column.right("±ERROR", 12),
        Column.left("UNIT", 10));

    private static final int RULE_WIDTH = 141;

    private ResultsTable() {
    }

    public static void print(Console console, List<ResultRow> rows) {
        if (rows.isEmpty()) {
            console.println("No results found.");
            return;
        }
        var table = new Table(console, RULE_WIDTH, COLUMNS);
        table.printHeader();
        for (ResultRow r : rows) {
            String shortName = r.benchmarkName().contains(".")
                ? r.benchmarkName().substring(r.benchmarkName().lastIndexOf('.') + 1)
                : r.benchmarkName();
            table.printRow(List.of(
                Cell.plain(truncate(shortName, 44)),
                Cell.plain(r.requestId()),
                Cell.plain(truncate(r.benchmarkType(), 13)),
                Cell.plain(r.mode() != null ? r.mode() : ""),
                number(console, r.score()),
                number(console, r.scoreError()),
                Cell.plain(r.scoreUnit() != null ? r.scoreUnit() : "")));
        }
    }

    /** An unknown value is de-emphasised, so it cannot be read as a measurement. */
    private static Cell number(Console console, double value) {
        String text = String.format("%.3f", value);
        return Double.isFinite(value) ? Cell.plain(text) : new Cell(text, console::faint);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
