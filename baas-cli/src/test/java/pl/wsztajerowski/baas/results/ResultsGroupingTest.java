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

    @Test
    void keepsTheHighestScoringRunPerGroup() {
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
}
