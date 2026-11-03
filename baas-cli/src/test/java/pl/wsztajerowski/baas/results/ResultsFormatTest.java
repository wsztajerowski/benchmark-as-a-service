package pl.wsztajerowski.baas.results;

import pl.wsztajerowski.baas.testsupport.TestRows;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.model.JcstressSummary;
import pl.wsztajerowski.baas.model.SecondaryMetric;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code baas results query --format json | jq} is the documented reason result payloads stay on stdout,
 * so the payload has to be JSON a parser accepts — on any machine.
 */
@ResourceLock(Resources.LOCALE)
class ResultsFormatTest {

    private final StringWriter captured = new StringWriter();
    private final Console console = Console.plain(new PrintWriter(captured));
    private Locale originalLocale;

    @BeforeEach
    void rememberLocale() {
        originalLocale = Locale.getDefault();
    }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(originalLocale);
    }

    private static ResultRow row(double score, double error) {
        return TestRows.row().jobId("jmh-1").benchmarkName("com.example.MyBenchmark.run").score(score)
            .scoreError(error).createdAt("2026-08-12T21:36:13Z")
            .tags(Map.of("imageVersion", "1.0.0", "instanceType", "c5.2xlarge")).build();
    }

    private String render(String format, List<ResultRow> rows) throws Exception {
        var command = new ResultsQuerySubcommand();
        command.context.use(console);
        Method method = ResultsQuerySubcommand.class.getDeclaredMethod(
            format.equals("json") ? "printJson" : "printCsv", List.class);
        method.setAccessible(true);
        method.invoke(command, rows);
        return captured.toString();
    }

    /** A JCStress job of 40 tests, two of them failed and one errored. */
    private static ResultRow jcstress(int failed, int errors) {
        Map<String, String> failedTests = new java.util.HashMap<>();
        for (int i = 0; i < failed; i++) failedTests.put("org.example.Race" + i, "FORBIDDEN");
        Map<String, String> errorTests = new java.util.HashMap<>();
        for (int i = 0; i < errors; i++) errorTests.put("org.example.Crash" + i, "ERROR");
        return TestRows.row().jobId("jc-1").benchmarkName("(jcstress) jc-1").benchmarkType("jcstress").mode(null)
            .score(Double.NaN).scoreError(Double.NaN).scoreUnit("")
            .createdAt("2026-08-12T21:36:13Z").project("p")
            .jcstress(new JcstressSummary(40, 40 - failed - errors, failed, errors, failedTests, errorTests, Map.of())).build();
    }

    private String table(List<ResultRow> rows, boolean verbose) {
        ResultsTable.print(console, rows, false, verbose);
        return captured.toString();
    }

    /** G2: a JCStress job ends completed whatever its tests found; the verdict is in the score. */
    @Test
    void aJcstressRowsScoreIsPassedOverTotalMarkedWhenATestFailed() {
        String failing = table(List.of(jcstress(2, 1)), false);

        assertThat(failing).contains("37/40 ✗").contains("tests").doesNotContain("NaN");
    }

    @Test
    void aJcstressRowWhoseTestsAllPassedCarriesNoMark() {
        assertThat(table(List.of(jcstress(0, 0)), false)).contains("40/40").doesNotContain("✗");
    }

    @Test
    void verboseNamesTheFailingTestsAndTheSecondaryMetrics() {
        Locale.setDefault(Locale.ROOT);
        var jmh = TestRows.row().jobId("jmh-1").benchmarkName("com.example.MyBenchmark.run")
            .createdAt("2026-08-12T21:36:13Z").project("p")
            .secondaryMetrics(Map.of("gc.alloc.rate.norm", new SecondaryMetric(24.0, "B/op"), "gc.count", new SecondaryMetric(3.0, "counts"))).build();

        String out = table(List.of(jcstress(1, 1), jmh), true);

        assertThat(out).contains("failed  org.example.Crash0  org.example.Race0")
            .contains("metrics gc.alloc.rate.norm=24.000 B/op  gc.count=3.000 counts");
    }

    @Test
    void jsonCarriesTheVerdictAndTheSecondaryMetrics() throws Exception {
        var jmh = TestRows.row().jobId("jmh-1").benchmarkName("com.example.MyBenchmark.run")
            .createdAt("2026-08-12T21:36:13Z").project("p")
            .secondaryMetrics(Map.of("gc.alloc.rate.norm", new SecondaryMetric(24.0, "B/op"))).build();

        var parsed = new ObjectMapper().readTree(render("json", List.of(jcstress(2, 1), jmh)));

        var verdict = parsed.get(0).get("jcstress");
        assertThat(verdict.get("total").asInt()).isEqualTo(40);
        assertThat(verdict.get("passed").asInt()).isEqualTo(37);
        assertThat(verdict.get("failed").asInt()).isEqualTo(2);
        assertThat(verdict.get("errors").asInt()).isEqualTo(1);
        assertThat(verdict.get("failedTests")).extracting(com.fasterxml.jackson.databind.JsonNode::asText)
            .containsExactly("org.example.Crash0", "org.example.Race0", "org.example.Race1");
        assertThat(parsed.get(0).get("secondaryMetrics").isEmpty()).isTrue();
        assertThat(parsed.get(1).get("jcstress").isNull()).isTrue();
        assertThat(parsed.get(1).get("secondaryMetrics").get("gc.alloc.rate.norm").get("score").asDouble()).isEqualTo(24.0);
        assertThat(parsed.get(1).get("secondaryMetrics").get("gc.alloc.rate.norm").get("unit").asText()).isEqualTo("B/op");
    }

    /**
     * A locale whose decimal separator is a comma turns {@code 8234574.73} into
     * {@code 8234574,73}, which is not a JSON number and splits a CSV column in two. Found on a
     * real job: the default locale here produced a document jq refused outright.
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
     * document has to stay parseable on exactly the jobs a user is most likely to be inspecting.
     */
    @Test
    void nonFiniteScoreErrorBecomesJsonNull() throws Exception {
        String json = render("json", List.of(row(1000.0, Double.NaN)));

        var parsed = new ObjectMapper().readTree(json);
        assertThat(parsed.get(0).get("scoreError").isNull()).isTrue();
        assertThat(parsed.get(0).get("score").asDouble()).isEqualTo(1000.0);
    }

    /** R9: an unknown score is an empty cell, never a number, and the column count holds. */
    @Test
    void anUnknownScoreIsAnEmptyCsvCell() throws Exception {
        var lines = render("csv", List.of(row(Double.NaN, Double.NaN))).strip().lines().toList();
        String[] header = lines.getFirst().split(",", -1);
        String[] cells = lines.get(1).split(",", -1);

        assertThat(cells).hasSameSizeAs(header);
        assertThat(cells[java.util.Arrays.asList(header).indexOf("score")]).isEmpty();
        assertThat(cells[java.util.Arrays.asList(header).indexOf("scoreError")]).isEmpty();
        assertThat(lines.get(1)).doesNotContain("NaN");
    }

    @Test
    void anUnknownScoreIsJsonNull() throws Exception {
        var parsed = new ObjectMapper().readTree(render("json", List.of(row(Double.NaN, Double.NaN))));

        assertThat(parsed.get(0).get("score").isNull()).isTrue();
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
        var untagged = TestRows.row().jobId("jmh-old").benchmarkName("com.example.Old.run")
            .createdAt("2026-01-01T00:00:00Z").build();

        var parsed = new ObjectMapper().readTree(render("json", List.of(untagged)));

        assertThat(parsed.get(0).get("imageVersion").isNull()).isTrue();
        assertThat(parsed.get(0).get("instanceType").isNull()).isTrue();
    }

    /** G17: a JSON row names its project and the library that produced it. */
    @Test
    void jsonNamesTheProjectAndTheKind() throws Exception {
        var parsed = new ObjectMapper().readTree(render("json", List.of(jcstress(0, 0))));

        assertThat(parsed.get(0).get("project").asText()).isEqualTo("p");
        assertThat(parsed.get(0).get("kind").asText()).isEqualTo("JCSTRESS");
    }

    /** X7: a runner can write tags and names; an escape sequence in one must not reach the terminal. */
    @Test
    void runnerWrittenTextReachesTheTableWithoutControlCharacters() {
        var hostile = TestRows.row().jobId("jmh-1").benchmarkName("com.example.My\u001b]0;pwned\u0007Benchmark.run")
            .benchmarkType("jmh\u001b[2J").score(1.5).scoreError(0.25)
            .createdAt("2026-08-12T21:36:13Z").tags(Map.of("note", "ok\u001b[31mred\u009b")).build();

        String out = table(List.of(hostile), true);

        assertThat(out).doesNotContain("\u001b", "\u0007", "\u009b").contains("note=ok[31mred");
    }

    /** W5: a JCStress row has no mode; it is JSON null and an empty CSV cell, never the text "null". */
    @Test
    void aJcstressRowsAbsentModeIsJsonNullAndAnEmptyCsvCell() throws Exception {
        var parsed = new ObjectMapper().readTree(render("json", List.of(jcstress(0, 0))));
        assertThat(parsed.get(0).get("mode").isNull()).isTrue();

        captured.getBuffer().setLength(0);
        var lines = render("csv", List.of(jcstress(0, 0))).strip().lines().toList();
        String[] header = lines.getFirst().split(",", -1);
        String[] cells = lines.get(1).split(",", -1);
        assertThat(cells[java.util.Arrays.asList(header).indexOf("mode")]).isEmpty();
        assertThat(lines.get(1)).doesNotContain("null");
    }

    /** A profiler perturbs the score it measures, so a profiled row is marked and the mark explained. */
    @Test
    void aProfiledScoreIsMarkedAndTheDigitsStayAligned() {
        var profiled = TestRows.row().jobId("20260820T174432812Z-a3f9c21b").benchmarkName("com.example.Q.offer")
            .score(41790355.108).createdAt("2026-08-20T17:44:32.812Z")
            .tags(Map.of("profilers", "async,gc")).build();
        var clean = TestRows.row().jobId("20260820T174432812Z-b7e4d0f2").benchmarkName("com.example.Q.offer")
            .score(48213977.412).createdAt("2026-08-20T17:44:32.812Z").build();

        String out = table(List.of(clean, profiled), false);

        var lines = out.lines().toList();
        String cleanLine = lines.stream().filter(l -> l.contains("b7e4d0f2")).findFirst().orElseThrow();
        String profiledLine = lines.stream().filter(l -> l.contains("a3f9c21b")).findFirst().orElseThrow();
        // The table formats in the default locale, so the expected text is formatted the same way.
        String profiledScore = String.format("%.3f", 41790355.108);
        String cleanScore = String.format("%.3f", 48213977.412);
        assertThat(profiledLine).contains(profiledScore + "* ops/s");
        assertThat(cleanLine).contains(cleanScore + "  ops/s").doesNotContain("*");
        assertThat(cleanLine.indexOf(cleanScore)).isEqualTo(profiledLine.indexOf(profiledScore));
        assertThat(out).contains(ResultsTable.PROFILED_FOOTNOTE);
    }

    /** A row stored under a retired profiled type carries no profilers tag, and was profiled all the same. */
    @Test
    void aRowOfARetiredProfiledTypeIsMarkedAndJcstressCellsKeepTheirEdge() {
        var legacy = TestRows.row().jobId("20260820T174432812Z-a3f9c21b").benchmarkName("com.example.Q.offer")
            .benchmarkType("jmh-with-async").score(41790355.108)
            .createdAt("2026-08-20T17:44:32.812Z").tags(Map.of("type", "jmh-with-async")).build();
        var clean = TestRows.row().jobId("20260820T174432812Z-b7e4d0f2").benchmarkName("com.example.Q.offer")
            .score(48213977.412).createdAt("2026-08-20T17:44:32.812Z").build();

        String out = table(List.of(clean, legacy, jcstress(0, 0)), false);

        var lines = out.lines().toList();
        String legacyLine = lines.stream().filter(l -> l.contains("a3f9c21b")).findFirst().orElseThrow();
        String jcstressLine = lines.stream().filter(l -> l.contains("jc-1")).findFirst().orElseThrow();
        assertThat(legacyLine).contains(String.format("%.3f", 41790355.108) + "* ops/s");
        // Both cells end in the mark's column: the JCStress verdict is padded where a score is marked.
        assertThat(jcstressLine.indexOf("40/40") + "40/40".length()).isEqualTo(legacyLine.indexOf("*"));
        assertThat(out).contains(ResultsTable.PROFILED_FOOTNOTE);
    }

    @Test
    void aTableWithNoProfiledRowHasNoMarkAndNoFootnote() {
        var clean = TestRows.row().jobId("20260820T174432812Z-b7e4d0f2").benchmarkName("com.example.Q.offer")
            .score(48213977.412).createdAt("2026-08-20T17:44:32.812Z").build();

        String out = table(List.of(clean), false);

        assertThat(out).doesNotContain("*").doesNotContain(ResultsTable.PROFILED_FOOTNOTE);
        assertThat(out.lines().filter(l -> l.contains("b7e4d0f2")).findFirst().orElseThrow())
            .contains(String.format("%.3f", 48213977.412) + " ops/s");
    }

    /**
     * Truncating the identifier at 17 landed inside the old {@code <type>-<date>} prefix, so two
     * runs of the same type on the same day rendered identically — and the identifier is the value
     * a user copies into {@code baas jobs download}, so a truncated one is unusable.
     */
    @Test
    void theJobIdentifierRendersWhole() {
        String jobId = "20260820T174432812Z-a3f9c21b";
        var row = TestRows.row().jobId(jobId).benchmarkName("com.example.MyBenchmark.run")
            .createdAt("2026-08-20T17:44:32.812Z").build();

        ResultsTable.print(console, List.of(row));

        assertThat(captured.toString()).contains(jobId);
    }

    /**
     * Issue #114: the cell showed the method alone, so two classes' {@code closedWorld} methods read
     * identically. The package is what goes; JSON and CSV keep the fully qualified name.
     */
    @Test
    void theBenchmarkCellNamesTheSimpleClassAndTheMethod() {
        var row = TestRows.row().jobId("20260820T174432812Z-a3f9c21b")
            .benchmarkName("io.roadrunner.bench.ClosedWorldBenchmark.closedWorld")
            .createdAt("2026-08-20T17:44:32.812Z").build();

        ResultsTable.print(console, List.of(row));

        assertThat(captured.toString()).contains("ClosedWorldBenchmark.closedWorld").doesNotContain("io.roadrunner");
    }

    @Test
    void aBenchmarkNameLongerThanItsColumnIsCutWithoutMovingTheNextColumn() {
        var longName = TestRows.row().jobId("20260820T174432812Z-a3f9c21b")
            .benchmarkName("io.roadrunner.bench.ClosedWorldBenchmark.closedWorldBenchmarkWithMetrics")
            .createdAt("2026-08-20T17:44:32.812Z").build();
        var shortName = TestRows.row().jobId("20260820T174432812Z-b7e4d0f2").benchmarkName("io.roadrunner.bench.B.run")
            .createdAt("2026-08-20T17:44:32.812Z").build();

        ResultsTable.print(console, List.of(longName, shortName));

        var lines = captured.toString().lines().toList();
        assertThat(lines.get(2)).startsWith("ClosedWorldBenchmark.closedWorldBenchmarkWi…");
        assertThat(lines.get(2).indexOf("a3f9c21b")).isEqualTo(lines.get(3).indexOf("b7e4d0f2"));
    }

    @Test
    void twoJobsOfOneTypeOnOneDayRenderDistinctly() {
        var first = TestRows.row().jobId("20260820T174432812Z-a3f9c21b").benchmarkName("com.example.B.run")
            .createdAt("2026-08-20T17:44:32.812Z").build();
        var second = TestRows.row().jobId("20260820T174432812Z-b7e4d0f2").benchmarkName("com.example.B.run").score(2.0)
            .createdAt("2026-08-20T17:44:32.812Z").build();

        ResultsTable.print(console, List.of(first, second));

        String out = captured.toString();
        assertThat(out).contains("a3f9c21b").contains("b7e4d0f2");
    }

    /** Tag values are free-form: quotes, backslashes and control characters must still parse. */
    @Test
    void jsonCarriesEveryTagAndKeepsTheScoreError() throws Exception {
        var tagged = TestRows.row().jobId("jmh-1").benchmarkName("com.example.MyBenchmark.run").scoreError(0.25)
            .createdAt("2026-08-12T21:36:13Z")
            .tags(Map.of("branch", "main", "note", "say \"hi\" \\ tab\there")).build();

        var parsed = new ObjectMapper().readTree(render("json", List.of(tagged)));

        assertThat(parsed.get(0).get("scoreError").asDouble()).isEqualTo(0.25);
        assertThat(parsed.get(0).get("tags").get("branch").asText()).isEqualTo("main");
        assertThat(parsed.get(0).get("tags").get("note").asText()).isEqualTo("say \"hi\" \\ tab\there");
    }

    /** The first CSV field that can carry a comma, a semicolon or a quote — always quoted. */
    @Test
    void csvQuotesTheTagsColumn() throws Exception {
        Locale.setDefault(Locale.forLanguageTag("pl-PL"));
        var tagged = TestRows.row().jobId("jmh-1").benchmarkName("com.example.MyBenchmark.run").score(1.5)
            .scoreError(0.25).createdAt("2026-08-12T21:36:13Z")
            .tags(Map.of("note", "a,b;\"c\"", "branch", "main")).build();

        var lines = render("csv", List.of(tagged)).strip().lines().toList();

        assertThat(lines.getFirst()).endsWith(",scoreError,scoreUnit,createdAt,imageVersion,instanceType,tags,params");
        assertThat(lines.get(1))
            .contains("1.5,0.25")
            .endsWith(",\"branch=main;note=a,b;\"\"c\"\"\",\"\"");
    }

    /** Review A11: a sweep's variant is told apart by its params, in both machine formats. */
    @Test
    void jsonAndCsvCarryTheParamsOfASweepVariant() throws Exception {
        var variant = TestRows.row().jobId("jmh-1").benchmarkName("com.acme.MapLookup.get").score(1.5).scoreError(0.25)
            .createdAt("2026-08-12T21:36:13Z").tags(Map.of("branch", "main"))
            .project("lynx-journal").params(Map.of("size", "10", "impl", "hash")).build();

        var parsed = new ObjectMapper().readTree(render("json", List.of(variant)));
        assertThat(parsed.get(0).get("params").get("impl").asText()).isEqualTo("hash");
        assertThat(parsed.get(0).get("params").get("size").asText()).isEqualTo("10");
        assertThat(parsed.get(0).get("benchmarkName").asText())
            .as("the name stays plain, so existing jq filters keep matching")
            .isEqualTo("com.acme.MapLookup.get");

        // render() appends to one captured buffer, so the CSV row is the last line, not the second.
        assertThat(render("csv", List.of(variant)).strip().lines().toList().getLast())
            .endsWith(",\"branch=main\",\"impl=hash;size=10\"");
    }

    @Test
    void jsonGivesABenchmarkWithoutParamsAnEmptyObject() throws Exception {
        var plain = TestRows.row().jobId("jmh-1").benchmarkName("com.example.MyBenchmark.run").score(1.5)
            .scoreError(0.25).createdAt("2026-08-12T21:36:13Z").build();

        var parsed = new ObjectMapper().readTree(render("json", List.of(plain)));
        assertThat(parsed.get(0).get("params").isEmpty()).isTrue();
    }
}
