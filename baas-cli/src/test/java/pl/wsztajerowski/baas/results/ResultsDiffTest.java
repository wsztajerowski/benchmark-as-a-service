package pl.wsztajerowski.baas.results;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.model.JcstressSummary;
import pl.wsztajerowski.baas.model.MeasurementKind;
import pl.wsztajerowski.baas.results.ResultsDiff.Row;
import pl.wsztajerowski.baas.results.ResultsDiff.Verdict;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ResultsDiffTest {

    private static final String A = "20260724T120000000Z-a3f9c21b";
    private static final String B = "20260811T093000000Z-b7e4d0f2";

    static ResultRow jmh(String job, String benchmark, String mode, double score, double error, String unit,
                         Map<String, String> params, Map<String, String> tags) {
        return new ResultRow(job, benchmark, tags.getOrDefault("type", "jmh"), mode, score, error, unit,
            "2026-08-20T17:44:32.812Z", tags, "p", params, null, Map.of(), MeasurementKind.JMH);
    }

    static ResultRow jmh(String job, String benchmark, String mode, double score, double error) {
        return jmh(job, benchmark, mode, score, error, "ops/s", Map.of(), Map.of("type", "jmh"));
    }

    static ResultRow jcstress(String job) {
        return new ResultRow(job, "(jcstress) " + job, "jcstress", null, Double.NaN, Double.NaN, "",
            "2026-08-20T17:44:32.812Z", Map.of("type", "jcstress"), "p", Map.of(),
            new JcstressSummary(42, 41, 1, 0, Map.of("T", "FORBIDDEN"), Map.of(), Map.of()), Map.of(),
            MeasurementKind.JCSTRESS);
    }

    // --- the library guard ---

    @Test
    void jmhAgainstJcstressIsRefusedNamingBothLibraries() {
        String refusal = ResultsDiff.refusal(A, List.of(jmh(A, "x.Foo.bar", "thrpt", 1, 0.1)), B, List.of(jcstress(B)));

        assertThat(refusal).contains("Job " + A + " is JMH", "job " + B + " is JCStress", "not comparable");
    }

    @Test
    void theGuardReadsTheStoredKindNotTheTypeTag() {
        // A JMH measurement whose type tag claims jcstress is still JMH: the tag is free-form.
        var mislabelled = jmh(B, "x.Foo.bar", "thrpt", 1, 0.1, "ops/s", Map.of(), Map.of("type", "jcstress"));

        assertThat(ResultsDiff.refusal(A, List.of(jmh(A, "x.Foo.bar", "thrpt", 1, 0.1)), B, List.of(mislabelled)))
            .isNull();
    }

    @Test
    void twoJcstressJobsAreRefusedAsNotSupported() {
        String refusal = ResultsDiff.refusal(A, List.of(jcstress(A)), B, List.of(jcstress(B)));

        assertThat(refusal).contains("not supported", "baas results query --job-id " + A)
            .doesNotContain("different libraries");
    }

    @Test
    void aJcstressJobAgainstAnEmptyOneIsRefusedAsNotSupported() {
        assertThat(ResultsDiff.refusal(A, List.of(jcstress(A)), B, List.of())).contains("not supported");
    }

    @Test
    void jmhVariantsAreCompared() {
        var plain = jmh(A, "x.Foo.bar", "thrpt", 1, 0.1, "ops/s", Map.of(), Map.of("type", "jmh"));
        var async = jmh(B, "x.Foo.bar", "thrpt", 1, 0.1, "ops/s", Map.of(), Map.of("type", "jmh-with-async"));
        var prof = jmh(B, "x.Foo.bar", "thrpt", 1, 0.1, "ops/s", Map.of(), Map.of("type", "jmh-with-prof"));

        assertThat(ResultsDiff.refusal(A, List.of(plain), B, List.of(async))).isNull();
        assertThat(ResultsDiff.refusal(A, List.of(async), B, List.of(prof))).isNull();
    }

    @Test
    void aJobOfMixedKindsIsRefusedAsCorrupt() {
        assertThat(ResultsDiff.refusal(A, List.of(jmh(A, "x.Foo.bar", "thrpt", 1, 0.1), jcstress(A)),
            B, List.of(jmh(B, "x.Foo.bar", "thrpt", 1, 0.1))))
            .contains("more than one library");
    }

    @Test
    void anEmptySidePassesTheGuard() {
        assertThat(ResultsDiff.refusal(A, List.of(), B, List.of(jmh(B, "x.Foo.bar", "thrpt", 1, 0.1)))).isNull();
        assertThat(ResultsDiff.refusal(A, List.of(), B, List.of())).isNull();
    }

    // --- pairing ---

    @Test
    void aSweepsVariantsPairSeparately() {
        var rows = ResultsDiff.compare(
            List.of(sized(A, "10", 100), sized(A, "100", 50)),
            List.of(sized(B, "100", 55), sized(B, "10", 101)));

        assertThat(rows).hasSize(2).allSatisfy(row -> {
            assertThat(row.a()).isNotNull();
            assertThat(row.b()).isNotNull();
            assertThat(row.a().params()).isEqualTo(row.b().params());
        });
        assertThat(rows).extracting(row -> row.a().paramsKey()).containsExactly("size=10", "size=100");
    }

    @Test
    void modesPairSeparately() {
        var rows = ResultsDiff.compare(
            List.of(jmh(A, "x.Foo.bar", "thrpt", 1, 0.1), jmh(A, "x.Foo.bar", "avgt", 2, 0.1)),
            List.of(jmh(B, "x.Foo.bar", "avgt", 2, 0.1), jmh(B, "x.Foo.bar", "thrpt", 1, 0.1)));

        assertThat(rows).extracting(row -> row.a().mode() + "/" + row.b().mode()).containsExactly("avgt/avgt", "thrpt/thrpt");
    }

    @Test
    void oneSidedRowsFollowThePairsAThenB() {
        var rows = ResultsDiff.compare(
            List.of(jmh(A, "x.Foo.zzz", "thrpt", 1, 0.1), jmh(A, "x.Foo.baz", "avgt", 1, 0.1)),
            List.of(jmh(B, "x.Foo.zzz", "thrpt", 1, 0.1), jmh(B, "x.Foo.new", "thrpt", 1, 0.1)));

        assertThat(rows).extracting(Row::verdict)
            .containsExactly(Verdict.INDISTINGUISHABLE, Verdict.ONLY_IN_A, Verdict.ONLY_IN_B);
        assertThat(rows.get(1).a().benchmarkName()).isEqualTo("x.Foo.baz");
        assertThat(rows.get(1).b()).isNull();
        assertThat(rows.get(2).b().benchmarkName()).isEqualTo("x.Foo.new");
        assertThat(rows.get(2).a()).isNull();
    }

    @Test
    void oneSideEmptyMakesEveryRowOneSided() {
        var rows = ResultsDiff.compare(List.of(jmh(A, "x.Foo.bar", "thrpt", 1, 0.1), jmh(A, "x.Foo.baz", "thrpt", 1, 0.1)), List.of());

        assertThat(rows).extracting(Row::verdict).containsOnly(Verdict.ONLY_IN_A).hasSize(2);
    }

    @Test
    void bothSidesEmptyIsNoRows() {
        assertThat(ResultsDiff.compare(List.of(), List.of())).isEmpty();
    }

    // --- judgement ---

    @Test
    void anOverlappingChangeShowsItsDeltaButNoVerdict() {
        Row row = ResultsDiff.pair(jmh(A, "x.Foo.bar", "thrpt", 1234.5, 40.1), jmh(B, "x.Foo.bar", "thrpt", 1210.0, 38.7));

        assertThat(row.deltaPercent()).isCloseTo(-2.0, within(0.05));
        assertThat(row.verdict()).isEqualTo(Verdict.INDISTINGUISHABLE);
    }

    @Test
    void aSlowerTimePerOperationIsARegression() {
        Row row = ResultsDiff.pair(jmh(A, "x.Foo.baz", "avgt", 812.3, 5.2), jmh(B, "x.Foo.baz", "avgt", 905.9, 6.0));

        assertThat(row.deltaPercent()).isCloseTo(11.5, within(0.05));
        assertThat(row.verdict()).isEqualTo(Verdict.REGRESSION);
    }

    @Test
    void aHigherThroughputIsAnImprovement() {
        Row row = ResultsDiff.pair(jmh(A, "x.Foo.q", "thrpt", 501.0, 3.1), jmh(B, "x.Foo.q", "thrpt", 560.4, 2.9));

        assertThat(row.deltaPercent()).isCloseTo(11.9, within(0.05));
        assertThat(row.verdict()).isEqualTo(Verdict.IMPROVEMENT);
    }

    @Test
    void aLowerThroughputIsARegressionAndAFasterTimeAnImprovement() {
        assertThat(ResultsDiff.judge(jmh(A, "x", "thrpt", 100, 1), jmh(B, "x", "thrpt", 90, 1))).isEqualTo(Verdict.REGRESSION);
        assertThat(ResultsDiff.judge(jmh(A, "x", "avgt", 100, 1), jmh(B, "x", "avgt", 90, 1))).isEqualTo(Verdict.IMPROVEMENT);
    }

    @Test
    void sampleAndSingleShotAreTimePerOperation() {
        assertThat(ResultsDiff.judge(jmh(A, "x", "sample", 100, 1), jmh(B, "x", "sample", 120, 1))).isEqualTo(Verdict.REGRESSION);
        assertThat(ResultsDiff.judge(jmh(A, "x", "ss", 100, 1), jmh(B, "x", "ss", 80, 1))).isEqualTo(Verdict.IMPROVEMENT);
    }

    @Test
    void touchingIntervalsAreIndistinguishable() {
        // [100 ± 5] and [110 ± 5] meet at 105.
        assertThat(ResultsDiff.judge(jmh(A, "x", "thrpt", 100, 5), jmh(B, "x", "thrpt", 110, 5)))
            .isEqualTo(Verdict.INDISTINGUISHABLE);
    }

    @Test
    void anUnknownErrorIsNotJudgedButStillHasADelta() {
        Row row = ResultsDiff.pair(jmh(A, "x", "avgt", 14.2, Double.NaN), jmh(B, "x", "avgt", 13.1, Double.NaN));

        assertThat(row.verdict()).isEqualTo(Verdict.UNKNOWN);
        assertThat(row.deltaPercent()).isCloseTo(-7.7, within(0.05));
    }

    @Test
    void anUnknownScoreHasNoDelta() {
        Row row = ResultsDiff.pair(jmh(A, "x", "avgt", Double.NaN, 1), jmh(B, "x", "avgt", 13.1, 1));

        assertThat(row.verdict()).isEqualTo(Verdict.UNKNOWN);
        assertThat(row.deltaPercent()).isNaN();
    }

    @Test
    void aZeroBaselineHasNoDelta() {
        assertThat(ResultsDiff.deltaPercent(0, 5)).isNaN();
    }

    @Test
    void differentUnitsAreShownNotComputed() {
        Row row = ResultsDiff.pair(
            jmh(A, "x", "thrpt", 1234.5, 1, "ops/ms", Map.of(), Map.of()),
            jmh(B, "x", "thrpt", 1198000, 1, "ops/s", Map.of(), Map.of()));

        assertThat(row.verdict()).isEqualTo(Verdict.UNITS_DIFFER);
        assertThat(row.deltaPercent()).isNaN();
        assertThat(row.a().score()).isEqualTo(1234.5);
        assertThat(row.b().score()).isEqualTo(1198000);
    }

    // --- environment ---

    @Test
    void aDifferentImageIsReportedAndTheHeaderTagsAreNot() {
        var a = jmh(A, "x", "thrpt", 1, 0.1, "ops/s", Map.of(),
            Map.of("type", "jmh", "instanceType", "c5.large", "imageVersion", "1.3.0", "jdk", "25.0.4"));
        var b = jmh(B, "x", "thrpt", 1, 0.1, "ops/s", Map.of(),
            Map.of("type", "jmh-with-async", "instanceType", "c5.2xlarge", "imageVersion", "1.4.0", "jdk", "25.0.4"));

        assertThat(ResultsDiff.environmentDifferences(List.of(a), List.of(b)))
            .containsExactly("imageVersion: 1.3.0 → 1.4.0");
    }

    @Test
    void equalEnvironmentsAndAnEmptySideReportNothing() {
        var a = jmh(A, "x", "thrpt", 1, 0.1, "ops/s", Map.of(), Map.of("imageVersion", "1.3.0"));
        var b = jmh(B, "x", "thrpt", 1, 0.1, "ops/s", Map.of(), Map.of("imageVersion", "1.3.0"));

        assertThat(ResultsDiff.environmentDifferences(List.of(a), List.of(b))).isEmpty();
        assertThat(ResultsDiff.environmentDifferences(List.of(a), List.of())).isEmpty();
    }

    private static ResultRow sized(String job, String size, double score) {
        return jmh(job, "x.Foo.bar", "thrpt", score, 1, "ops/s", Map.of("size", size), Map.of());
    }
}
