package pl.wsztajerowski.baas.console;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.results.ResultRow;
import pl.wsztajerowski.baas.results.ResultsTable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two properties: a plain table is byte-identical to the {@code printf} it replaced, and colouring
 * one changes nothing but the escape sequences.
 */
class TableTest {

    private static final String ESC = "\u001b";
    // The printf the table replaced, minus the ±ERROR column that left the table.
    private static final String OLD_RESULTS_FORMAT = "%-45s %-28s %-14s %-8s %14s %-10s%n";

    private static final List<ResultRow> ROWS = List.of(
        new ResultRow("20260820T174432812Z-a3f9c21b", "com.example.MyBenchmark.measure", "jmh",
            "thrpt", 8234.123456, 12.3456, "ops/s", "2026-08-20T17:44:32.812Z", Map.of()),
        new ResultRow("20260820T174432812Z-b7e4d0f2",
            "com.example.AVeryLongBenchmarkClassName.aMethodNameThatWillNotFitTheColumn", "jmh-with-async-profiler",
            null, Double.NaN, Double.NaN, null, "2026-08-20T17:44:32.812Z", Map.of()));

    private static String render(boolean colour) {
        var out = new StringWriter();
        ResultsTable.print(Console.withFlags(new PrintWriter(out), colour, colour), ROWS);
        return out.toString();
    }

    private static String strip(String text) {
        return text.replaceAll(ESC + "\\[[0-9;]*m", "");
    }

    /** The output the removed {@code ResultsQueryService.printTable} produced, less ±ERROR. */
    @Test
    void thePlainResultsTableIsByteIdenticalToTheOldPrintf() {
        String expected = String.format(OLD_RESULTS_FORMAT,
                "BENCHMARK", "JOB_ID", "TYPE", "MODE", "SCORE", "UNIT")
            + "-".repeat(128) + System.lineSeparator()
            + String.format(OLD_RESULTS_FORMAT, "measure", "20260820T174432812Z-a3f9c21b", "jmh",
                "thrpt", String.format("%.3f", 8234.123456), "ops/s")
            + String.format(OLD_RESULTS_FORMAT, "aMethodNameThatWillNotFitTheColumn",
                "20260820T174432812Z-b7e4d0f2", "jmh-with-asy…", "",
                String.format("%.3f", Double.NaN), "");

        assertThat(render(false)).isEqualTo(expected);
    }

    @Test
    void theScoreErrorIsNotAColumn() {
        assertThat(render(false).lines().findFirst().orElseThrow()).doesNotContain("ERROR");
    }

    @Test
    void aProjectColumnLeadsOnlyWhenAskedFor() {
        var out = new StringWriter();
        ResultsTable.print(Console.plain(new PrintWriter(out)), List.of(row("lynx-journal", Map.of())), true, false);

        assertThat(out.toString().lines().findFirst().orElseThrow()).startsWith("PROJECT ");
        assertThat(out.toString().lines().toList().get(2)).startsWith("lynx-journal ");
        assertThat(render(false).lines().findFirst().orElseThrow()).doesNotContain("PROJECT");
    }

    @Test
    void tagLinesFollowEachRowOnlyWhenAskedFor() {
        var rows = List.of(row("p", Map.of("jdk", "25.0.4", "branch", "main")));
        var withTags = new StringWriter();
        var without = new StringWriter();
        ResultsTable.print(Console.plain(new PrintWriter(withTags)), rows, false, true);
        ResultsTable.print(Console.plain(new PrintWriter(without)), rows, false, false);

        assertThat(withTags.toString().lines().toList().get(3)).isEqualTo("    tags    branch=main  jdk=25.0.4");
        assertThat(without.toString().lines()).hasSize(3);
    }

    /**
     * Review A11: params go under the row with -v, not into the BENCHMARK column — a sweep can
     * declare many, and the column would have no room for them. Params first, then tags, each
     * labelled so a row with only one kind cannot be misread.
     */
    @Test
    void aSweepVariantShowsALabelledParamsLineAboveItsTags() {
        var variant = new ResultRow("20260820T174432812Z-a3f9c21b", "com.acme.MapLookup.get", "jmh", "thrpt",
            1.0, 0.1, "ops/s", "2026-08-20T17:44:32.812Z", Map.of("branch", "main"), "p",
            Map.of("size", "10", "impl", "hash"));
        var verbose = new StringWriter();
        var plain = new StringWriter();
        ResultsTable.print(Console.plain(new PrintWriter(verbose)), List.of(variant), false, true);
        ResultsTable.print(Console.plain(new PrintWriter(plain)), List.of(variant), false, false);

        var lines = verbose.toString().lines().toList();
        assertThat(lines.get(2)).startsWith("get ");
        assertThat(lines.get(3)).isEqualTo("    params  impl=hash  size=10");
        assertThat(lines.get(4)).isEqualTo("    tags    branch=main");
        assertThat(plain.toString()).doesNotContain("impl=hash");
    }

    /** Shown only under --all-jobs; faint, so it reads as set aside — and still aligned. */
    @Test
    void anExcludedRowIsFaintAndStillAligned() {
        var rows = List.of(row("p", Map.of("exclude_from_results", "true")), row("p", Map.of()));
        var coloured = new StringWriter();
        var plain = new StringWriter();
        ResultsTable.print(Console.withFlags(new PrintWriter(coloured), true, true), rows, true, false);
        ResultsTable.print(Console.plain(new PrintWriter(plain)), rows, true, false);

        assertThat(coloured.toString().lines().toList().get(2)).startsWith(ESC + "[2m");
        assertThat(coloured.toString().lines().toList().get(3)).doesNotContain(ESC + "[2m");
        assertThat(strip(coloured.toString())).isEqualTo(plain.toString());
    }

    private static ResultRow row(String project, Map<String, String> tags) {
        return new ResultRow("20260820T174432812Z-a3f9c21b", "com.example.Bench.run", "jmh", "thrpt",
            1.0, 0.1, "ops/s", "2026-08-20T17:44:32.812Z", tags, project);
    }

    @Test
    void colourChangesNothingButEscapeSequences() {
        String coloured = render(true);

        assertThat(coloured).contains(ESC);
        assertThat(strip(coloured)).isEqualTo(render(false));
    }

    @Test
    void theHeaderIsBoldAndAnUnknownValueIsFaint() {
        String coloured = render(true);
        String header = coloured.lines().findFirst().orElseThrow();
        String unknownRow = coloured.lines().toList().get(3);

        assertThat(header).contains(ESC + "[1mBENCHMARK");
        assertThat(unknownRow).contains(ESC + "[2mNaN");
        assertThat(coloured.lines().toList().get(2)).doesNotContain(ESC + "[2m");
    }

    @Test
    void anEmptyResultSaysSo() {
        var out = new StringWriter();
        ResultsTable.print(Console.plain(new PrintWriter(out)), List.of());

        assertThat(out.toString()).isEqualTo("No results found." + System.lineSeparator());
    }
}
