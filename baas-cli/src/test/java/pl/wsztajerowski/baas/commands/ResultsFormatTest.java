package pl.wsztajerowski.baas.commands;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.results.ResultRow;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code baas results --format json | jq} is the documented reason result payloads stay on stdout,
 * so the payload has to be JSON a parser accepts — on any machine.
 */
class ResultsFormatTest {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private Locale originalLocale;

    @BeforeEach
    void redirect() {
        originalOut = System.out;
        originalLocale = Locale.getDefault();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restore() {
        System.setOut(originalOut);
        Locale.setDefault(originalLocale);
    }

    private static ResultRow row(double score, double error) {
        return new ResultRow("jmh-1", "com.example.MyBenchmark.run", "jmh", "thrpt",
            score, error, "ops/s", "2026-08-12T21:36:13Z",
            Map.of("imageVersion", "1.0.0", "instanceType", "c5.2xlarge"));
    }

    private String render(String format, List<ResultRow> rows) throws Exception {
        var command = new ResultsCommand();
        Method method = ResultsCommand.class.getDeclaredMethod(
            format.equals("json") ? "printJson" : "printCsv", List.class);
        method.setAccessible(true);
        method.invoke(command, rows);
        return captured.toString(StandardCharsets.UTF_8);
    }

    /**
     * A locale whose decimal separator is a comma turns {@code 8234574.73} into
     * {@code 8234574,73}, which is not a JSON number and splits a CSV column in two. Found on a
     * real run: the default locale here produced a document jq refused outright.
     */
    @Test
    void jsonIsValidUnderALocaleThatUsesACommaDecimalSeparator() throws Exception {
        Locale.setDefault(Locale.forLanguageTag("pl-PL"));

        String json = render("json", List.of(row(8234574.731914, 12.5)));

        assertThat(json).doesNotContain("8234574,");
        var parsed = new ObjectMapper().readTree(json);
        assertThat(parsed.get(0).get("score").asDouble()).isEqualTo(8234574.731914);
        assertThat(parsed.get(0).get("imageVersion").asText()).isEqualTo("1.0.0");
        assertThat(parsed.get(0).get("instanceType").asText()).isEqualTo("c5.2xlarge");
    }

    /**
     * JMH reports NaN score error for a single-iteration run, and JSON has no NaN literal — the
     * document has to stay parseable on exactly the runs a user is most likely to be inspecting.
     */
    @Test
    void nonFiniteScoreErrorBecomesJsonNull() throws Exception {
        String json = render("json", List.of(row(1000.0, Double.NaN)));

        var parsed = new ObjectMapper().readTree(json);
        assertThat(parsed.get(0).get("scoreError").isNull()).isTrue();
        assertThat(parsed.get(0).get("score").asDouble()).isEqualTo(1000.0);
    }

    @Test
    void csvKeepsOneColumnPerFieldUnderACommaDecimalLocale() throws Exception {
        Locale.setDefault(Locale.forLanguageTag("pl-PL"));

        String csv = render("csv", List.of(row(8234574.731914, 12.5)));
        var lines = csv.strip().lines().toList();

        assertThat(lines).hasSize(2);
        assertThat(lines.getFirst().split(",", -1))
            .as("header and row must agree on column count")
            .hasSameSizeAs(lines.get(1).split(",", -1));
        assertThat(lines.get(1)).contains("8234574.731914", "1.0.0", "c5.2xlarge");
    }

    @Test
    void untaggedHistoricalRowsRenderAsJsonNullRatherThanTheStringNull() throws Exception {
        var untagged = new ResultRow("jmh-old", "com.example.Old.run", "jmh", "thrpt",
            1.0, 0.1, "ops/s", "2026-01-01T00:00:00Z", Map.of());

        var parsed = new ObjectMapper().readTree(render("json", List.of(untagged)));

        assertThat(parsed.get(0).get("imageVersion").isNull()).isTrue();
        assertThat(parsed.get(0).get("instanceType").isNull()).isTrue();
    }
}
