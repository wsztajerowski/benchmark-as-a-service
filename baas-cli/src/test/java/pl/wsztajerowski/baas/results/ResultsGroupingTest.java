package pl.wsztajerowski.baas.results;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResultsGroupingTest {

    private static ResultRow row(String benchmark, String branch, double score) {
        return new ResultRow("req-" + score, benchmark, "jmh", "thrpt",
            score, 1.0, "ops/s", "2026-08-19T09:00:00Z",
            branch == null ? Map.of() : Map.of(ResultsFilters.BRANCH, branch));
    }

    private static ResultRow variant(String size, String branch, double score) {
        return new ResultRow("req-" + score, "com.acme.MapLookup.get", "jmh", "thrpt",
            score, 1.0, "ops/s", "2026-08-19T09:00:00Z", Map.of(ResultsFilters.BRANCH, branch), "p",
            Map.of("size", size));
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

        var best = ResultsGrouping.bestPerGroup(rows, ResultsFilters.BRANCH);

        assertThat(best).extracting(ResultRow::score).containsExactlyInAnyOrder(950.0, 40.0);
    }

    @Test
    void displayOrderKeepsEachVariantsGroupsTogether() {
        var rows = List.of(
            variant("100000", "main", 40.0),
            variant("10", "feature-x", 990.0),
            variant("100000", "feature-x", 45.0),
            variant("10", "main", 950.0));

        var sorted = ResultsGrouping.sortedForDisplay(rows);

        assertThat(sorted).extracting(row -> row.params().get("size"))
            .containsExactly("10", "10", "100000", "100000");
    }

    @Test
    void keepsTheHighestScoringJobPerGroup() {
        var rows = List.of(
            row("com.example.Bench.run", "main", 100.0),
            row("com.example.Bench.run", "main", 300.0),
            row("com.example.Bench.run", "main", 200.0));

        var best = ResultsGrouping.bestPerGroup(rows, ResultsFilters.BRANCH);

        assertThat(best).singleElement()
            .extracting(ResultRow::score)
            .isEqualTo(300.0);
    }

    @Test
    void keepsTheSameBenchmarkUnderTwoGroupValuesSeparate() {
        var rows = List.of(
            row("com.example.Bench.run", "main", 100.0),
            row("com.example.Bench.run", "feature-x", 50.0));

        var best = ResultsGrouping.bestPerGroup(rows, ResultsFilters.BRANCH);

        assertThat(best).hasSize(2);
        assertThat(best).extracting(row -> row.tag(ResultsFilters.BRANCH))
            .containsExactlyInAnyOrder("main", "feature-x");
    }

    @Test
    void collectsRowsWithNoGroupTagRatherThanDroppingThem() {
        var rows = List.of(
            row("com.example.Bench.run", null, 10.0),
            row("com.example.Bench.run", null, 20.0));

        var best = ResultsGrouping.bestPerGroup(rows, ResultsFilters.BRANCH);

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

        assertThat(ResultsGrouping.bestPerGroup(rows, ResultsFilters.BRANCH)).hasSize(2);
    }

    @Test
    void differentBenchmarksNeverShareAGroup() {
        var rows = List.of(
            row("com.example.A.run", "main", 10.0),
            row("com.example.B.run", "main", 20.0));

        assertThat(ResultsGrouping.bestPerGroup(rows, ResultsFilters.BRANCH)).hasSize(2);
    }

    @Test
    void aNonFiniteScoreNeverWinsRegardlessOfArrivalOrder() {
        var finiteFirst = List.of(
            row("com.example.Bench.run", "main", 100.0),
            row("com.example.Bench.run", "main", Double.NaN));
        var nanFirst = List.of(
            row("com.example.Bench.run", "main", Double.NaN),
            row("com.example.Bench.run", "main", 100.0));

        assertThat(ResultsGrouping.bestPerGroup(finiteFirst, ResultsFilters.BRANCH))
            .singleElement().extracting(ResultRow::score).isEqualTo(100.0);
        assertThat(ResultsGrouping.bestPerGroup(nanFirst, ResultsFilters.BRANCH))
            .as("NaN compares false against everything, so a naive > would let scan order decide")
            .singleElement().extracting(ResultRow::score).isEqualTo(100.0);
    }

    @Test
    void groupsByAnyTagNotJustBranch() {
        var rows = List.of(
            new ResultRow("r1", "com.example.Bench.run", "jmh", "thrpt", 10.0, 1.0, "ops/s", "t",
                Map.of("jdk", "25.0.3")),
            new ResultRow("r2", "com.example.Bench.run", "jmh", "thrpt", 20.0, 1.0, "ops/s", "t",
                Map.of("jdk", "25.0.4")));

        assertThat(ResultsGrouping.bestPerGroup(rows, "jdk")).hasSize(2);
    }

    /** Under --all-projects two projects can share a benchmark name; neither may absorb the other. */
    @Test
    void twoProjectsNeverShareAGroup() {
        var rows = List.of(
            new ResultRow("r1", "com.example.Bench.run", "jmh", "thrpt", 100.0, 1.0, "ops/s", "t",
                Map.of(ResultsFilters.BRANCH, "main"), "lynx-journal"),
            new ResultRow("r2", "com.example.Bench.run", "jmh", "thrpt", 300.0, 1.0, "ops/s", "t",
                Map.of(ResultsFilters.BRANCH, "main"), "other-project"));

        assertThat(ResultsGrouping.bestPerGroup(rows, ResultsFilters.BRANCH))
            .extracting(ResultRow::project)
            .containsExactlyInAnyOrder("lynx-journal", "other-project");
    }

    @Test
    void displaySortsByProjectBeforeBenchmark() {
        var rows = List.of(
            new ResultRow("r1", "a.A.run", "jmh", "thrpt", 1.0, 1.0, "ops/s", "t", Map.of(), "zeta"),
            new ResultRow("r2", "z.Z.run", "jmh", "thrpt", 1.0, 1.0, "ops/s", "t", Map.of(), "alpha"));

        assertThat(ResultsGrouping.sortedForDisplay(rows))
            .extracting(ResultRow::project)
            .containsExactly("alpha", "zeta");
    }
}
