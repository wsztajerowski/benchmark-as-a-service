package pl.wsztajerowski.baas.results;

import pl.wsztajerowski.baas.testsupport.TestRows;
import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.model.TagKeys;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResultsGroupingTest {

    private static ResultRow row(String benchmark, String branch, double score) {
        return TestRows.row().jobId("req-" + score).benchmarkName(benchmark).score(score).scoreError(1.0)
            .createdAt("2026-08-19T09:00:00Z")
            .tags(branch == null ? Map.of() : Map.of(TagKeys.BRANCH, branch)).build();
    }

    private static ResultRow inMode(String mode, double score, String createdAt) {
        return TestRows.row().jobId("job-" + createdAt).benchmarkName("com.example.Bench.run").mode(mode)
            .score(score).scoreError(0.0).scoreUnit("avgt".equals(mode) ? "ns/op" : "ops/s")
            .createdAt(createdAt).tags(Map.of("branch", "main")).project("p").build();
    }

    private static ResultRow variant(String size, String branch, double score) {
        return TestRows.row().jobId("req-" + score).benchmarkName("com.acme.MapLookup.get").score(score)
            .scoreError(1.0).createdAt("2026-08-19T09:00:00Z").tags(Map.of(TagKeys.BRANCH, branch))
            .project("p").params(Map.of("size", size)).build();
    }

    /**
     * Review A11: a sweep's variants are different workloads. Grouping them together would keep
     * only the cheapest variant, and compare that across branches.
     */
    @Test
    void keepsTheBestPerVariantRatherThanAcrossTheSweep() {
        var rows = List.of(
            variant("10", "main", 900.0),
            variant("10", "main", 950.0),
            variant("100000", "main", 40.0));

        var best = ResultsGrouping.bestPerGroup(rows, TagKeys.BRANCH);

        assertThat(best).extracting(ResultRow::score).containsExactlyInAnyOrder(950.0, 40.0);
    }

    @Test
    void lowerIsBetterForTimePerOperation() {
        var rows = List.of(inMode("avgt", 12.0, "a"), inMode("avgt", 9.0, "b"), inMode("avgt", 15.0, "c"));

        assertThat(ResultsGrouping.bestPerGroup(rows, TagKeys.BRANCH))
            .singleElement().extracting(ResultRow::score).isEqualTo(9.0);
    }

    @Test
    void everyTimePerOperationModeKeepsTheLowest() {
        for (String mode : List.of("avgt", "sample", "ss")) {
            var rows = List.of(inMode(mode, 5.0, "a"), inMode(mode, 3.0, "b"));
            assertThat(ResultsGrouping.bestPerGroup(rows, TagKeys.BRANCH))
                .as(mode).singleElement().extracting(ResultRow::score).isEqualTo(3.0);
        }
    }

    @Test
    void twoModesOfOneBenchmarkAreNeverCompared() {
        var rows = List.of(inMode("thrpt", 1000.0, "a"), inMode("avgt", 9.0, "b"));

        assertThat(ResultsGrouping.bestPerGroup(rows, TagKeys.BRANCH)).hasSize(2);
    }

    @Test
    void theDefaultOrderIsNewestFirst() {
        var rows = List.of(inMode("thrpt", 1.0, "2026-10-01T00:00:00.000Z"),
            inMode("thrpt", 2.0, "2026-10-03T00:00:00.000Z"),
            inMode("thrpt", 3.0, "2026-10-02T00:00:00.000Z"));

        assertThat(ResultsGrouping.sorted(rows, "created", false))
            .extracting(ResultRow::score).containsExactly(2.0, 3.0, 1.0);
        assertThat(ResultsGrouping.sorted(rows, "created", true))
            .extracting(ResultRow::score).containsExactly(1.0, 3.0, 2.0);
    }

    /** R9: an absent score is unknown, not zero, so it can never win a lower-is-better group. */
    @Test
    void aScoreTheItemDoesNotCarryNeverWinsALowerIsBetterGroup() {
        var missing = ResultRow.from(new pl.wsztajerowski.baas.model.StoredMeasurement(
            "p", "job-missing", java.time.Instant.parse("2026-08-19T09:00:00Z"),
            pl.wsztajerowski.baas.model.BenchmarkType.values()[0], "com.example.Bench", "run", "avgt",
            Map.of(), null, null, "ns/op", Map.of(), null, Map.of("branch", "main"), null, null, null));

        assertThat(missing.score()).isNaN();
        assertThat(missing.scoreError()).isNaN();
        assertThat(ResultsGrouping.bestPerGroup(List.of(missing, inMode("avgt", 9.0, "b")), TagKeys.BRANCH))
            .singleElement().extracting(ResultRow::score).isEqualTo(9.0);
    }

    @Test
    void anUnknownScoreSortsLastInBothDirections() {
        var rows = List.of(inMode("thrpt", 1.0, "a"), inMode("thrpt", Double.NaN, "b"), inMode("thrpt", 2.0, "c"));

        assertThat(ResultsGrouping.sorted(rows, "score", false)).extracting(ResultRow::score)
            .containsExactly(2.0, 1.0, Double.NaN);
        assertThat(ResultsGrouping.sorted(rows, "score", true)).extracting(ResultRow::score)
            .containsExactly(1.0, 2.0, Double.NaN);
    }

    @Test
    void sortingByScoreIsDescendingUnlessAscending() {
        var rows = List.of(inMode("thrpt", 1.0, "a"), inMode("thrpt", 3.0, "b"), inMode("thrpt", 2.0, "c"));

        assertThat(ResultsGrouping.sorted(rows, "score", false)).extracting(ResultRow::score).containsExactly(3.0, 2.0, 1.0);
        assertThat(ResultsGrouping.sorted(rows, "score", true)).extracting(ResultRow::score).containsExactly(1.0, 2.0, 3.0);
    }

    @Test
    void displayOrderKeepsEachVariantsGroupsTogether() {
        var rows = List.of(
            variant("100000", "main", 40.0),
            variant("10", "feature-x", 990.0),
            variant("100000", "feature-x", 45.0),
            variant("10", "main", 950.0));

        var sorted = ResultsGrouping.sorted(rows, "benchmark", true);

        assertThat(sorted).extracting(row -> row.params().get("size"))
            .containsExactly("10", "10", "100000", "100000");
    }

    @Test
    void keepsTheHighestScoringJobPerGroup() {
        var rows = List.of(
            row("com.example.Bench.run", "main", 100.0),
            row("com.example.Bench.run", "main", 300.0),
            row("com.example.Bench.run", "main", 200.0));

        var best = ResultsGrouping.bestPerGroup(rows, TagKeys.BRANCH);

        assertThat(best).singleElement()
            .extracting(ResultRow::score)
            .isEqualTo(300.0);
    }

    @Test
    void keepsTheSameBenchmarkUnderTwoGroupValuesSeparate() {
        var rows = List.of(
            row("com.example.Bench.run", "main", 100.0),
            row("com.example.Bench.run", "feature-x", 50.0));

        var best = ResultsGrouping.bestPerGroup(rows, TagKeys.BRANCH);

        assertThat(best).hasSize(2);
        assertThat(best).extracting(row -> row.tag(TagKeys.BRANCH))
            .containsExactlyInAnyOrder("main", "feature-x");
    }

    @Test
    void collectsRowsWithNoGroupTagRatherThanDroppingThem() {
        var rows = List.of(
            row("com.example.Bench.run", null, 10.0),
            row("com.example.Bench.run", null, 20.0));

        var best = ResultsGrouping.bestPerGroup(rows, TagKeys.BRANCH);

        assertThat(best)
            .as("untagged history predates the branch tag and must still be reported")
            .singleElement()
            .extracting(ResultRow::score)
            .isEqualTo(20.0);
    }

    @Test
    void keepsUntaggedRowsSeparateFromTaggedOnes() {
        var rows = List.of(
            row("com.example.Bench.run", null, 10.0),
            row("com.example.Bench.run", "main", 20.0));

        assertThat(ResultsGrouping.bestPerGroup(rows, TagKeys.BRANCH)).hasSize(2);
    }

    @Test
    void differentBenchmarksNeverShareAGroup() {
        var rows = List.of(
            row("com.example.A.run", "main", 10.0),
            row("com.example.B.run", "main", 20.0));

        assertThat(ResultsGrouping.bestPerGroup(rows, TagKeys.BRANCH)).hasSize(2);
    }

    @Test
    void aNonFiniteScoreNeverWinsRegardlessOfArrivalOrder() {
        var finiteFirst = List.of(
            row("com.example.Bench.run", "main", 100.0),
            row("com.example.Bench.run", "main", Double.NaN));
        var nanFirst = List.of(
            row("com.example.Bench.run", "main", Double.NaN),
            row("com.example.Bench.run", "main", 100.0));

        assertThat(ResultsGrouping.bestPerGroup(finiteFirst, TagKeys.BRANCH))
            .singleElement().extracting(ResultRow::score).isEqualTo(100.0);
        assertThat(ResultsGrouping.bestPerGroup(nanFirst, TagKeys.BRANCH))
            .as("NaN compares false against everything, so a naive > would let scan order decide")
            .singleElement().extracting(ResultRow::score).isEqualTo(100.0);
    }

    @Test
    void groupsByAnyTagNotJustBranch() {
        var rows = List.of(
            TestRows.row().jobId("r1").benchmarkName("com.example.Bench.run").score(10.0).scoreError(1.0)
                .createdAt("t").tags(Map.of("jdkVersion", "25.0.3")).build(),
            TestRows.row().jobId("r2").benchmarkName("com.example.Bench.run").score(20.0).scoreError(1.0)
                .createdAt("t").tags(Map.of("jdkVersion", "25.0.4")).build());

        assertThat(ResultsGrouping.bestPerGroup(rows, "jdkVersion")).hasSize(2);
    }

    /** Under --all-projects two projects can share a benchmark name; neither may absorb the other. */
    @Test
    void twoProjectsNeverShareAGroup() {
        var rows = List.of(
            TestRows.row().jobId("r1").benchmarkName("com.example.Bench.run").score(100.0).scoreError(1.0)
                .createdAt("t").tags(Map.of(TagKeys.BRANCH, "main")).project("lynx-journal").build(),
            TestRows.row().jobId("r2").benchmarkName("com.example.Bench.run").score(300.0).scoreError(1.0)
                .createdAt("t").tags(Map.of(TagKeys.BRANCH, "main")).project("other-project").build());

        assertThat(ResultsGrouping.bestPerGroup(rows, TagKeys.BRANCH))
            .extracting(ResultRow::project)
            .containsExactlyInAnyOrder("lynx-journal", "other-project");
    }

    @Test
    void sortingByProjectOrdersProjectsAlphabetically() {
        var rows = List.of(
            TestRows.row().jobId("r1").benchmarkName("a.A.run").scoreError(1.0).createdAt("t").project("zeta").build(),
            TestRows.row().jobId("r2").benchmarkName("z.Z.run").scoreError(1.0).createdAt("t").project("alpha").build());

        assertThat(ResultsGrouping.sorted(rows, "project", true))
            .extracting(ResultRow::project)
            .containsExactly("alpha", "zeta");
    }
}
