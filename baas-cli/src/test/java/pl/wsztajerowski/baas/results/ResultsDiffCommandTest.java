package pl.wsztajerowski.baas.results;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import pl.wsztajerowski.baas.BaasApp;
import pl.wsztajerowski.baas.TestDeployments;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.model.MeasurementKind;
import pl.wsztajerowski.baas.model.SecondaryMetric;
import pl.wsztajerowski.baas.session.BaasConfig;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static pl.wsztajerowski.baas.results.ResultsDiffTest.jcstress;
import static pl.wsztajerowski.baas.results.ResultsDiffTest.jmh;

/** {@code baas results diff} as a user runs it: arguments, refusals, exit status and both payloads. */
class ResultsDiffCommandTest {

    private static final String A = "20260724T120000000Z-a3f9c21b";
    private static final String B = "20260811T093000000Z-b7e4d0f2";
    private static final String ESC = "\u001b";

    @TempDir Path dir;

    /**
     * The table formats for the reader's locale, as {@code results query}'s does; these assertions
     * are written with a {@code .}. JSON is locale-independent and has its own test.
     */
    private Locale originalLocale;

    @org.junit.jupiter.api.BeforeEach
    void rootLocale() {
        originalLocale = Locale.getDefault();
        Locale.setDefault(Locale.ROOT);
    }

    @org.junit.jupiter.api.AfterEach
    void restoreLocale() {
        Locale.setDefault(originalLocale);
    }

    /** Jobs the fake table holds; an id absent from it names no job. */
    private final Map<String, List<ResultRow>> jobs = new HashMap<>();
    private final List<String> lookedUp = new ArrayList<>();
    private boolean interactive;

    private record Captured(String out, String err, int exitCode) {}

    private Captured run(String... args) throws Exception {
        TestDeployments.write(dir, TestDeployments.DEFAULT);
        var out = new StringWriter();
        var diff = new ResultsDiffSubcommand() {
            @Override
            ResultsQueryService openResults(BaasConfig config, String tableName) {
                return new ResultsQueryService(null, tableName) {
                    @Override
                    public Optional<List<ResultRow>> queryJob(String jobId) {
                        lookedUp.add(jobId);
                        return Optional.ofNullable(jobs.get(jobId));
                    }

                    @Override
                    public void close() {
                    }
                };
            }
        };
        diff.console = Console.withFlags(new PrintWriter(out, true), interactive, interactive);
        CommandLine.IFactory defaults = CommandLine.defaultFactory();
        var cli = BaasApp.commandLine(new BaasApp(dir), new CommandLine.IFactory() {
            @Override
            @SuppressWarnings("unchecked")
            public <K> K create(Class<K> type) throws Exception {
                return type == ResultsDiffSubcommand.class ? (K) diff : defaults.create(type);
            }
        });
        var err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int code = cli.execute(args);
            return new Captured(out.toString(), err.toString(StandardCharsets.UTF_8), code);
        } finally {
            System.setErr(originalErr);
        }
    }

    private static Map<String, String> tags(String type, String instanceType, String imageVersion) {
        return Map.of("type", type, "instanceType", instanceType, "imageVersion", imageVersion);
    }

    private void twoJmhJobs() {
        jobs.put(A, List.of(
            jmh(A, "com.example.Foo.bar", "thrpt", 1234.5, 40.1, "ops/s", Map.of(), tags("jmh", "c5.large", "1.3.0")),
            jmh(A, "com.example.Foo.baz", "avgt", 812.3, 5.2, "ms/op", Map.of(), tags("jmh", "c5.large", "1.3.0")),
            jmh(A, "com.example.Foo.gone", "thrpt", 9, 1, "ops/s", Map.of(), tags("jmh", "c5.large", "1.3.0"))));
        jobs.put(B, List.of(
            jmh(B, "com.example.Foo.bar", "thrpt", 1210.0, 38.7, "ops/s", Map.of(), tags("jmh-with-async", "c5.2xlarge", "1.3.0")),
            jmh(B, "com.example.Foo.baz", "avgt", 905.9, 6.0, "ms/op", Map.of(), tags("jmh-with-async", "c5.2xlarge", "1.3.0")),
            jmh(B, "com.example.Foo.new", "thrpt", 7, 1, "ops/s", Map.of(), tags("jmh-with-async", "c5.2xlarge", "1.3.0"))));
    }

    // --- arguments ---

    @Test
    void diffIsAVerbOfResultsWithNoTopLevelAlias() {
        CommandLine root = BaasApp.commandLine(new BaasApp());

        assertThat(root.getSubcommands().get("results").getSubcommands()).containsKeys("query", "diff");
        assertThat(root.getSubcommands()).doesNotContainKey("diff");
    }

    @Test
    void aMalformedIdIsAUsageErrorBeforeAnyLookup() throws Exception {
        Captured result = run("results", "diff", "main", B);

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.err()).contains("'main' is not a job id");
        assertThat(lookedUp).isEmpty();
    }

    @Test
    void theSameJobTwiceIsAUsageError() throws Exception {
        Captured result = run("results", "diff", A, A);

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(lookedUp).isEmpty();
    }

    @Test
    void anUnknownFormatIsAUsageError() throws Exception {
        assertThat(run("results", "diff", A, B, "--format", "csv").exitCode()).isEqualTo(2);
        assertThat(lookedUp).isEmpty();
    }

    @Test
    void anUnknownFirstJobIsRefusedWithoutLookingUpTheSecond() throws Exception {
        jobs.put(B, List.of());

        Captured result = run("results", "diff", A, B);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.err()).contains("No job found with id '" + A + "'");
        assertThat(lookedUp).containsExactly(A);
    }

    // --- refusals ---

    @Test
    void jmhAgainstJcstressIsRefusedAndPrintsNoRow() throws Exception {
        jobs.put(A, List.of(jmh(A, "com.example.Foo.bar", "thrpt", 1, 0.1)));
        jobs.put(B, List.of(jcstress(B)));

        Captured result = run("results", "diff", A, B);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.err()).contains("different libraries are not comparable");
        assertThat(result.out()).isEmpty();
    }

    @Test
    void twoJcstressJobsAreRefused() throws Exception {
        jobs.put(A, List.of(jcstress(A)));
        jobs.put(B, List.of(jcstress(B)));

        Captured result = run("results", "diff", A, B);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.err()).contains("not supported", "baas results query --job-id");
    }

    // --- the table ---

    @Test
    void theTableOpensWithTheHeaderThenPairsThenOneSidedRows() throws Exception {
        twoJmhJobs();

        Captured result = run("results", "diff", A, B);

        assertThat(result.exitCode()).isEqualTo(0);
        String out = result.out();
        assertThat(out).contains("INSTANCE  c5.large", "c5.2xlarge", "TYPE      jmh", "jmh-with-async");
        assertThat(out.indexOf("INSTANCE")).isLessThan(out.indexOf("BENCHMARK"));
        assertThat(out).contains("Foo.bar", "-2.0 %", "~");
        assertThat(out).contains("Foo.baz", "+11.5 %", "regression");
        assertThat(out.indexOf("Foo.baz")).isLessThan(out.indexOf("only in A"));
        assertThat(out.indexOf("only in A")).isLessThan(out.indexOf("only in B"));
    }

    @Test
    void aRegressionStillExitsZero() throws Exception {
        twoJmhJobs();

        assertThat(run("results", "diff", A, B).exitCode()).isZero();
    }

    @Test
    void otherEnvironmentDifferencesGoToStderrNotStdout() throws Exception {
        twoJmhJobs();
        jobs.put(B, jobs.get(B).stream().map(r -> jmh(B, r.benchmarkName(), r.mode(), r.score(), r.scoreError(),
            r.scoreUnit(), Map.of(), tags("jmh", "c5.large", "1.4.0"))).toList());

        Captured result = run("results", "diff", A, B);

        assertThat(result.err()).contains("imageVersion: 1.3.0 → 1.4.0", "baas jobs diff " + A + " " + B);
        assertThat(result.out()).doesNotContain("imageVersion").doesNotContain("jobs diff");
    }

    @Test
    void equalEnvironmentsWarnNothing() throws Exception {
        twoJmhJobs();

        assertThat(run("results", "diff", A, B).err()).doesNotContain("different environments");
    }

    @Test
    void differentUnitsReadAsSuch() throws Exception {
        jobs.put(A, List.of(jmh(A, "com.example.Foo.tu", "thrpt", 1234.5, 1, "ops/ms", Map.of(), Map.of())));
        jobs.put(B, List.of(jmh(B, "com.example.Foo.tu", "thrpt", 1198000, 1, "ops/s", Map.of(), Map.of())));

        String out = run("results", "diff", A, B).out();

        assertThat(out).contains("units differ", "ops/ms → ops/s").doesNotContain(" %");
    }

    @Test
    void neitherJobHavingMeasurementsSaysSoAndExitsZero() throws Exception {
        jobs.put(A, List.of());
        jobs.put(B, List.of());

        Captured result = run("results", "diff", A, B);

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).contains("Neither job has measurements.");
    }

    @Test
    void oneJobWithoutMeasurementsListsTheOthersRowsOneSided() throws Exception {
        jobs.put(A, List.of());
        jobs.put(B, List.of(jmh(B, "com.example.Foo.bar", "thrpt", 1, 0.1)));

        Captured result = run("results", "diff", A, B);

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).contains("only in B").doesNotContain("Neither job");
    }

    @Test
    void secondaryMetricsAppearRawOnlyUnderVerbose() throws Exception {
        var metricA = Map.of("gc.alloc.rate.norm", new SecondaryMetric(48.0, "B/op"));
        var metricB = Map.of("gc.alloc.rate.norm", new SecondaryMetric(96.0, "B/op"));
        jobs.put(A, List.of(new ResultRow(A, "com.example.Foo.bar", "jmh-with-prof", "avgt", 10, 0.1, "ns/op",
            "2026-08-20T17:44:32.812Z", Map.of(), "p", Map.of(), null, metricA, MeasurementKind.JMH)));
        jobs.put(B, List.of(new ResultRow(B, "com.example.Foo.bar", "jmh-with-prof", "avgt", 10, 0.1, "ns/op",
            "2026-08-20T17:44:32.812Z", Map.of(), "p", Map.of(), null, metricB, MeasurementKind.JMH)));

        String plain = run("results", "diff", A, B).out();
        String verbose = run("results", "diff", "-v", A, B).out();

        assertThat(plain).doesNotContain("gc.alloc.rate.norm");
        String metricLine = verbose.lines().filter(l -> l.contains("gc.alloc.rate.norm")).findFirst().orElseThrow();
        assertThat(metricLine).contains("48.000 B/op", "96.000 B/op").doesNotContain("%").doesNotContain("regression");
    }

    @Test
    void aMetricOnOneSideOnlyShowsTheOtherAsUnknown() throws Exception {
        var metric = Map.of("gc.count", new SecondaryMetric(3.0, "counts"));
        jobs.put(A, List.of(new ResultRow(A, "com.example.Foo.bar", "jmh-with-prof", "avgt", 10, 0.1, "ns/op",
            "2026-08-20T17:44:32.812Z", Map.of(), "p", Map.of(), null, metric, MeasurementKind.JMH)));
        jobs.put(B, List.of(jmh(B, "com.example.Foo.bar", "avgt", 10, 0.1, "ns/op", Map.of(), Map.of())));

        String metricLine = run("results", "diff", "-v", A, B).out().lines()
            .filter(l -> l.contains("gc.count")).findFirst().orElseThrow();

        assertThat(metricLine).contains("A 3.000 counts", "B n/a");
    }

    @Test
    void tagsAreComparedOnlyUnderVerbose() throws Exception {
        jobs.put(A, List.of(jmh(A, "com.example.Foo.bar", "thrpt", 1, 0.1, "ops/s", Map.of(),
            Map.of("type", "jmh", "instanceType", "c5.large", "branch", "main", "jdk", "25.0.4"))));
        jobs.put(B, List.of(jmh(B, "com.example.Foo.bar", "thrpt", 1, 0.1, "ops/s", Map.of(),
            Map.of("type", "jmh", "instanceType", "c5.large", "branch", "feature-x", "commit", "abc"))));

        String plain = run("results", "diff", A, B).out();
        String verbose = run("results", "diff", "-v", A, B).out();

        assertThat(plain).doesNotContain("TAGS").doesNotContain("feature-x");
        assertThat(verbose.lines()).contains("TAGS");
        assertThat(verbose.lines().filter(l -> l.startsWith("  branch"))).singleElement().asString()
            .matches("  branch +main +feature-x");
        assertThat(verbose.lines().filter(l -> l.startsWith("  commit"))).singleElement().asString()
            .matches("  commit +n/a +abc");
        // The header's two are not repeated, and the block comes before the table.
        assertThat(verbose.lines().filter(l -> l.startsWith("  instanceType") || l.startsWith("  type"))).isEmpty();
        assertThat(verbose.indexOf("TAGS")).isLessThan(verbose.indexOf("BENCHMARK"));
    }

    @Test
    void theTagsShareTheHeadersColumns() throws Exception {
        jobs.put(A, List.of(jmh(A, "com.example.Foo.bar", "thrpt", 1, 0.1, "ops/s", Map.of(),
            Map.of("type", "jmh", "instanceType", "c5.large", "hidden", "true",
                "cpuModel", "Intel(R) Xeon(R) Platinum 8124M CPU @ 3.00GHz"))));
        jobs.put(B, List.of(jmh(B, "com.example.Foo.bar", "thrpt", 1, 0.1, "ops/s", Map.of(),
            Map.of("type", "jmh", "instanceType", "c5.large", "hidden", "true",
                "cpuModel", "Intel(R) Xeon(R) Platinum 8275CL CPU @ 3.00GHz"))));

        List<String> lines = run("results", "diff", "-v", A, B).out().lines().toList();

        String job = lines.stream().filter(l -> l.startsWith("JOB")).findFirst().orElseThrow();
        String cpu = lines.stream().filter(l -> l.startsWith("  cpuModel")).findFirst().orElseThrow();
        String flag = lines.stream().filter(l -> l.startsWith("  hidden")).findFirst().orElseThrow();
        assertThat(cpu.indexOf("Intel")).isEqualTo(job.indexOf(A)).isEqualTo(flag.indexOf("true"));
        assertThat(cpu.lastIndexOf("Intel")).isEqualTo(job.indexOf(B)).isEqualTo(flag.lastIndexOf("true"));
    }

    @Test
    void aLongTagValueWrapsInsideItsColumn() throws Exception {
        String longA = "Incrementing_Synchronized -f 1 -wi 1 -i 3 --async-event=cpu -jvmArgs '-Xmx1g -Xms1g -XX:+UseParallelGC'";
        String longB = "Incrementing_Synchronized -f 1 -wi 1 -i 5 --async-event=wall -jvmArgs '-Xmx1g -Xms1g -XX:+UseG1GC -XX:MaxGCPauseMillis=50'";
        jobs.put(A, List.of(jmh(A, "com.example.Foo.bar", "thrpt", 1, 0.1, "ops/s", Map.of(),
            Map.of("type", "jmh", "cliParameters", longA, "jdk", "25.0.4"))));
        jobs.put(B, List.of(jmh(B, "com.example.Foo.bar", "thrpt", 1, 0.1, "ops/s", Map.of(),
            Map.of("type", "jmh", "cliParameters", longB, "jdk", "25.0.4"))));

        List<String> lines = run("results", "diff", "-v", A, B).out().lines().toList();

        int first = lines.indexOf(lines.stream().filter(l -> l.startsWith("  cliParameters")).findFirst().orElseThrow());
        String jdk = lines.stream().filter(l -> l.startsWith("  jdk")).findFirst().orElseThrow();
        int aColumn = jdk.indexOf("25.0.4");
        int bColumn = jdk.lastIndexOf("25.0.4");
        List<String> block = lines.subList(first, lines.indexOf(jdk));
        assertThat(block).hasSizeGreaterThan(1);
        // Every line stays within the table, and every continuation starts in A's or B's column.
        assertThat(lines).allSatisfy(l -> assertThat(l.length()).isLessThanOrEqualTo(152));
        for (String line : block.subList(1, block.size())) {
            assertThat(line.substring(0, aColumn)).isBlank();
        }
        String rejoinedA = String.join(" ", block.stream()
            .map(l -> l.substring(aColumn, Math.min(l.length(), bColumn)).strip()).filter(t -> !t.isEmpty()).toList());
        String rejoinedB = String.join(" ", block.stream()
            .filter(l -> l.length() > bColumn).map(l -> l.substring(bColumn).strip()).toList());
        assertThat(rejoinedA).isEqualTo(longA);
        assertThat(rejoinedB).isEqualTo(longB);
    }

    @Test
    void differentCliParametersWarnOnStderr() throws Exception {
        jobs.put(A, List.of(jmh(A, "com.example.Foo.bar", "thrpt", 1, 0.1, "ops/s", Map.of(),
            Map.of("type", "jmh", "cliParameters", "Foo -f 1 -i 3"))));
        jobs.put(B, List.of(jmh(B, "com.example.Foo.bar", "thrpt", 1, 0.1, "ops/s", Map.of(),
            Map.of("type", "jmh", "cliParameters", "Foo -f 1 -i 5"))));

        Captured result = run("results", "diff", A, B);

        assertThat(result.exitCode()).isZero();
        assertThat(result.err()).contains("different parameters", "Foo -f 1 -i 3 → Foo -f 1 -i 5");
        assertThat(result.out()).doesNotContain("different parameters");
    }

    @Test
    void equalCliParametersWarnNothing() throws Exception {
        jobs.put(A, List.of(jmh(A, "com.example.Foo.bar", "thrpt", 1, 0.1, "ops/s", Map.of(),
            Map.of("type", "jmh", "cliParameters", "Foo -f 1"))));
        jobs.put(B, List.of(jmh(B, "com.example.Foo.bar", "thrpt", 1, 0.1, "ops/s", Map.of(),
            Map.of("type", "jmh", "cliParameters", "Foo -f 1"))));

        assertThat(run("results", "diff", A, B).err()).doesNotContain("different parameters");
    }

    @Test
    void aDifferingTagIsEmphasisedAndAnEqualOneIsNot() throws Exception {
        jobs.put(A, List.of(jmh(A, "com.example.Foo.bar", "thrpt", 1, 0.1, "ops/s", Map.of(),
            Map.of("type", "jmh", "branch", "main", "jdk", "25.0.4"))));
        jobs.put(B, List.of(jmh(B, "com.example.Foo.bar", "thrpt", 1, 0.1, "ops/s", Map.of(),
            Map.of("type", "jmh", "branch", "feature-x", "jdk", "25.0.4"))));
        String plain = run("results", "diff", "-v", A, B).out();
        interactive = true;

        String coloured = run("results", "diff", "-v", A, B).out();

        assertThat(coloured.replaceAll(ESC + "\\[[0-9;]*m", "")).isEqualTo(plain);
        assertThat(coloured.lines().filter(l -> l.startsWith("  branch")).findFirst().orElseThrow()).contains(ESC + "[1m");
        assertThat(coloured.lines().filter(l -> l.startsWith("  jdk")).findFirst().orElseThrow()).doesNotContain(ESC);
    }

    @Test
    void paramsAppearInFullUnderEachRowOnlyUnderVerbose() throws Exception {
        var sweep = Map.of("payload", "a-rather-long-payload-name", "size", "1024");
        jobs.put(A, List.of(
            jmh(A, "com.example.Foo.bar", "thrpt", 1, 0.1, "ops/s", sweep, Map.of("type", "jmh")),
            jmh(A, "com.example.Foo.plain", "thrpt", 1, 0.1, "ops/s", Map.of(), Map.of("type", "jmh"))));
        jobs.put(B, List.of(
            jmh(B, "com.example.Foo.bar", "thrpt", 1, 0.1, "ops/s", sweep, Map.of("type", "jmh")),
            jmh(B, "com.example.Foo.bar", "thrpt", 1, 0.1, "ops/s", Map.of("size", "2048"), Map.of("type", "jmh"))));

        String plain = run("results", "diff", A, B).out();
        List<String> paramLines = run("results", "diff", "-v", A, B).out().lines()
            .filter(l -> l.trim().startsWith("params")).toList();

        assertThat(plain).doesNotContain("params");
        // One line per row that has params; a benchmark without any gets none.
        assertThat(paramLines).hasSize(2);
        assertThat(paramLines.get(0)).contains("A payload=a-rather-long-payload-name,size=1024",
            "B payload=a-rather-long-payload-name,size=1024");
        assertThat(paramLines.get(1)).contains("A n/a", "B size=2048");
    }

    @Test
    void aJobWithoutMeasurementsHeadsItsColumnWithUnknowns() throws Exception {
        jobs.put(A, List.of());
        jobs.put(B, List.of(jmh(B, "com.example.Foo.bar", "thrpt", 1, 0.1, "ops/s", Map.of(), tags("jmh", "c5.large", "1.3.0"))));

        String out = run("results", "diff", A, B).out();

        assertThat(out.lines().filter(l -> l.startsWith("INSTANCE")).findFirst().orElseThrow())
            .matches("INSTANCE +n/a +c5\\.large");
    }

    @Test
    void colouredVerdictsKeepTheColumnsWhereThePlainTableHasThem() throws Exception {
        twoJmhJobs();
        String plain = run("results", "diff", A, B).out();
        interactive = true;
        String coloured = run("results", "diff", A, B).out();

        assertThat(coloured).contains(ESC);
        assertThat(coloured.replaceAll(ESC + "\\[[0-9;]*m", "")).isEqualTo(plain);
        String regressionLine = coloured.lines().filter(l -> l.contains("regression")).findFirst().orElseThrow();
        assertThat(regressionLine).contains(ESC + "[31m");
        String overlapLine = coloured.lines().filter(l -> l.contains("Foo.bar")).findFirst().orElseThrow();
        assertThat(overlapLine).doesNotContain(ESC);
        // The header emphasises a differing instance type, never the two ids, which always differ.
        assertThat(coloured.lines().filter(l -> l.startsWith("INSTANCE")).findFirst().orElseThrow()).contains(ESC + "[1m");
        assertThat(coloured.lines().filter(l -> l.startsWith("JOB")).findFirst().orElseThrow()).doesNotContain(ESC);
    }

    // --- JSON ---

    @Test
    void jsonCarriesBothJobsAndEveryRowsVerdict() throws Exception {
        twoJmhJobs();

        Captured result = run("results", "diff", A, B, "--format", "json");
        JsonNode json = new ObjectMapper().readTree(result.out());

        assertThat(result.exitCode()).isZero();
        assertThat(json.get("a").get("jobId").asText()).isEqualTo(A);
        assertThat(json.get("a").get("instanceType").asText()).isEqualTo("c5.large");
        assertThat(json.get("b").get("type").asText()).isEqualTo("jmh-with-async");
        assertThat(json.get("b").get("tags").get("imageVersion").asText()).isEqualTo("1.3.0");
        JsonNode rows = json.get("rows");
        assertThat(rows).hasSize(4);
        assertThat(rows.get(0).get("benchmark").asText()).isEqualTo("com.example.Foo.bar");
        assertThat(rows.get(0).get("verdict").asText()).isEqualTo("indistinguishable");
        assertThat(rows.get(1).get("benchmark").asText()).isEqualTo("com.example.Foo.baz");
        assertThat(rows.get(1).get("verdict").asText()).isEqualTo("regression");
        assertThat(rows.get(1).get("deltaPercent").asDouble()).isBetween(11.5, 11.6);
        assertThat(rows.get(2).get("verdict").asText()).isEqualTo("only-in-a");
        assertThat(rows.get(2).get("b").isNull()).isTrue();
        assertThat(rows.get(2).get("deltaPercent").isNull()).isTrue();
        assertThat(rows.get(3).get("verdict").asText()).isEqualTo("only-in-b");
        assertThat(rows.get(1).get("a").get("unit").asText()).isEqualTo("ms/op");
        assertThat(rows.get(1).get("a").get("secondaryMetrics").isObject()).isTrue();
    }

    @Test
    void jsonUnderACommaDecimalLocaleIsStillJson() throws Exception {
        twoJmhJobs();
        Locale.setDefault(Locale.forLanguageTag("pl-PL"));
        interactive = true;
        String out = run("results", "diff", A, B, "--format", "json").out();

        assertThat(out).doesNotContain(ESC);
        assertThat(new ObjectMapper().readTree(out).get("rows").get(1).get("a").get("score").asDouble())
            .isEqualTo(812.3);
    }

    @Test
    void jsonOfTwoEmptyJobsHasNoRows() throws Exception {
        jobs.put(A, List.of());
        jobs.put(B, List.of());

        JsonNode json = new ObjectMapper().readTree(run("results", "diff", A, B, "--format", "json").out());

        assertThat(json.get("rows")).isEmpty();
        assertThat(json.get("a").get("instanceType").isNull()).isTrue();
    }

    @Test
    void helpNamesTheLibraryRuleAndTheGatingLine() {
        String usage = BaasApp.commandLine(new BaasApp())
            .getSubcommands().get("results").getSubcommands().get("diff")
            .getUsageMessage(CommandLine.Help.Ansi.OFF);

        assertThat(usage).contains("JCStress jobs are not compared", "jq -e", "baas jobs diff");
    }
}
