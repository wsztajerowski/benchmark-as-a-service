package pl.wsztajerowski.baas.results;

import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.console.Table;
import pl.wsztajerowski.baas.console.Table.Cell;
import pl.wsztajerowski.baas.console.Table.Column;
import pl.wsztajerowski.baas.model.SecondaryMetric;
import pl.wsztajerowski.baas.model.TagKeys;

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
     *                    tag, with any secondary metrics and a JCStress job's failing tests between.
     *                    Outside the column grid, so however many there are, no column moves
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
        // A score measured under a profiler is marked, since a profiler perturbs it. Only when some
        // row is marked do the others make room for the mark, so a table with none is unchanged.
        boolean anyProfiled = rows.stream().anyMatch(ResultsTable::profiled);
        for (ResultRow r : rows) {
            // A hidden row is shown only under --show-hidden, and faint so it reads as set aside.
            UnaryOperator<String> rowStyle = r.hidden() ? console::faint : UnaryOperator.identity();
            var cells = new ArrayList<Cell>();
            if (withProject) {
                cells.add(new Cell(truncate(r.project(), 23), rowStyle));
            }
            cells.add(new Cell(truncate(withoutPackage(r.benchmarkName()), 44), rowStyle));
            cells.add(new Cell(r.jobId(), rowStyle));
            cells.add(new Cell(truncate(r.benchmarkType(), 13), rowStyle));
            cells.add(new Cell(r.mode() != null ? r.mode() : "", rowStyle));
            if (r.jcstress() != null) {
                cells.add(verdict(console, r, rowStyle));
                cells.add(new Cell("tests", rowStyle));
            } else {
                cells.add(number(console, r.score(), rowStyle, anyProfiled ? (profiled(r) ? "*" : " ") : ""));
                cells.add(new Cell(r.scoreUnit() != null ? r.scoreUnit() : "", rowStyle));
            }
            table.printRow(cells);
            if (withTags && !r.params().isEmpty()) {
                console.println("    " + console.faint("params  " + tagLine(r.params())));
            }
            if (withTags && !r.secondaryMetrics().isEmpty()) {
                console.println("    " + console.faint("metrics " + metricLine(r.secondaryMetrics())));
            }
            if (withTags && r.testsFailed()) {
                var names = new TreeMap<String, String>(r.jcstress().failed());
                names.putAll(r.jcstress().errors());
                console.println("    " + console.faint("failed  " + String.join("  ", names.keySet())));
            }
            if (withTags && !r.tags().isEmpty()) {
                console.println("    " + console.faint("tags    " + tagLine(r.tags())));
            }
        }
        if (anyProfiled) {
            console.println("");
            console.println(PROFILED_FOOTNOTE);
        }
    }

    /** The footnote under a table holding at least one {@code *}. */
    static final String PROFILED_FOOTNOTE = "* measured under a profiler; -v names it in the profilers tag";

    private static boolean profiled(ResultRow r) {
        return r.jcstress() == null && r.tags().containsKey(TagKeys.PROFILERS);
    }

    /** Sorted by key so two rows' lines can be compared by eye. Used for params and tags alike. */
    static String tagLine(Map<String, String> tags) {
        return new TreeMap<>(tags).entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining("  "));
    }

    /** {@code name=score unit}, sorted by name like {@link #tagLine}; numbers formatted as the SCORE column's. */
    static String metricLine(Map<String, SecondaryMetric> metrics) {
        return new TreeMap<>(metrics).entrySet().stream()
            .map(e -> e.getKey() + "=" + String.format("%.3f", e.getValue().score())
                + (e.getValue().unit() == null || e.getValue().unit().isEmpty() ? "" : " " + e.getValue().unit()))
            .collect(Collectors.joining("  "));
    }

    /**
     * A JCStress row's score cell: {@code passed/total}, marked when any test failed or errored.
     * The job's status says only that JCStress ran; this is where its verdict shows.
     */
    private static Cell verdict(Console console, ResultRow r, UnaryOperator<String> rowStyle) {
        String text = r.jcstress().passedTests() + "/" + r.jcstress().totalTests();
        return r.testsFailed() ? new Cell(text + " ✗", console::red) : new Cell(text, rowStyle);
    }

    /** An unknown value is de-emphasised, so it cannot be read as a measurement. */
    private static Cell number(Console console, double value, UnaryOperator<String> rowStyle, String mark) {
        String text = String.format("%.3f", value) + mark;
        return Double.isFinite(value) ? new Cell(text, rowStyle) : new Cell(text, console::faint);
    }

    /**
     * {@code SimpleClass.method}: the method alone made two classes' same-named benchmarks read
     * identically (issue #114). JSON, CSV and {@code --benchmark-name} keep the fully qualified name.
     */
    static String withoutPackage(String benchmarkName) {
        int methodDot = benchmarkName.lastIndexOf('.');
        int classDot = methodDot > 0 ? benchmarkName.lastIndexOf('.', methodDot - 1) : -1;
        return benchmarkName.substring(classDot + 1);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
