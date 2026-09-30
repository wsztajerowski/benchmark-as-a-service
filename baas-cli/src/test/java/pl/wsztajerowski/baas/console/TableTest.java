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
    private static final String OLD_RESULTS_FORMAT = "%-45s %-28s %-14s %-8s %14s %12s %-10s%n";

    private static final List<ResultRow> ROWS = List.of(
        new ResultRow("20260820T174432812Z-a3f9c21b", "com.example.MyBenchmark.measure", "jmh",
            "thrpt", 8234.123456, 12.3456, "ops/s", "2026-08-20T17:44:32.812Z", Map.of()),
        new ResultRow("20260820T174432812Z-b7e4d0f2",
            "com.example.AVeryLongBenchmarkClassName.aMethodNameThatWillNotFitTheColumn", "jmh-with-async-profiler",
            null, 1.0, Double.NaN, null, "2026-08-20T17:44:32.812Z", Map.of()));

    private static String render(boolean colour) {
        var out = new StringWriter();
        ResultsTable.print(Console.withFlags(new PrintWriter(out), colour, colour), ROWS);
        return out.toString();
    }

    private static String strip(String text) {
        return text.replaceAll(ESC + "\\[[0-9;]*m", "");
    }

    /** The output the removed {@code ResultsQueryService.printTable} produced, reproduced here. */
    @Test
    void thePlainResultsTableIsByteIdenticalToTheOldPrintf() {
        String expected = String.format(OLD_RESULTS_FORMAT,
                "BENCHMARK", "REQUEST_ID", "TYPE", "MODE", "SCORE", "±ERROR", "UNIT")
            + "-".repeat(141) + System.lineSeparator()
            + String.format(OLD_RESULTS_FORMAT, "measure", "20260820T174432812Z-a3f9c21b", "jmh",
                "thrpt", String.format("%.3f", 8234.123456), String.format("%.3f", 12.3456), "ops/s")
            + String.format(OLD_RESULTS_FORMAT, "aMethodNameThatWillNotFitTheColumn",
                "20260820T174432812Z-b7e4d0f2", "jmh-with-asy…", "",
                String.format("%.3f", 1.0), String.format("%.3f", Double.NaN), "");

        assertThat(render(false)).isEqualTo(expected);
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
