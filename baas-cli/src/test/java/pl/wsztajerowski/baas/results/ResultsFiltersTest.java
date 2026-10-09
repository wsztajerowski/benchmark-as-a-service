package pl.wsztajerowski.baas.results;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResultsFiltersTest {

    private static ResultRow row(String benchmark, Map<String, String> tags) {
        return new ResultRow("req-1", benchmark, "jmh", "thrpt",
            1.0, 0.1, "ops/s", "2026-08-19T09:00:00Z", tags);
    }

    @Test
    void repeatedTagsCombineConjunctively() {
        var both = row("A.run", Map.of("jdk", "25.0.4", "cpuArch", "aarch64"));
        var onlyOne = row("B.run", Map.of("jdk", "25.0.4"));

        var filtered = ResultsFilters.byTags(List.of(both, onlyOne),
            Map.of("jdk", "25.0.4", "cpuArch", "aarch64"));

        assertThat(filtered).containsExactly(both);
    }

    @Test
    void aCustomTagKeyFiltersJustLikeAKnownOne() {
        var tagged = row("A.run", Map.of("experiment", "gc-tuning"));
        var untagged = row("B.run", Map.of());

        assertThat(ResultsFilters.byTags(List.of(tagged, untagged), Map.of("experiment", "gc-tuning")))
            .containsExactly(tagged);
    }

    @Test
    void benchmarkNameMatchesAsASubstringRegex() {
        var queue = row("com.example.QueueBenchmark.offer", Map.of());
        var other = row("com.example.MapBenchmark.get", Map.of());

        assertThat(ResultsFilters.byBenchmarkName(List.of(queue, other), "Queue"))
            .containsExactly(queue);
    }

    @Test
    void benchmarkNameMatchesAFullyQualifiedName() {
        var target = row("pl.wsztajerowski.MyBenchmark.run", Map.of());

        assertThat(ResultsFilters.byBenchmarkName(List.of(target), "pl.wsztajerowski.MyBenchmark"))
            .containsExactly(target);
    }

    @Test
    void warnsWhenAnUnknownTagKeyMatchesNothing() {
        var rows = List.of(row("A.run", Map.of("branch", "main")));

        assertThat(ResultsFilters.unknownTagWarning(rows, Map.of("brunch", "main")))
            .hasValueSatisfying(warning -> assertThat(warning).contains("brunch"));
    }

    @Test
    void staysQuietWhenAnUnknownKeyIsActuallyCarriedBySomeRow() {
        var rows = List.of(row("A.run", Map.of("experiment", "gc-tuning")));

        assertThat(ResultsFilters.unknownTagWarning(rows, Map.of("experiment", "something-else")))
            .as("a custom key some row carries is a legitimate filter, not a typo")
            .isEmpty();
    }

    @Test
    void staysQuietForAKnownKeyThatSimplyMatchedNothing() {
        assertThat(ResultsFilters.unknownTagWarning(List.of(), Map.of("jdk", "25.0.9")))
            .as("a known key matching nothing is an ordinary empty result")
            .isEmpty();
    }

    @Test
    void anExcludedTagDropsTheRowsCarryingIt() {
        var ci = row("a.B.run", Map.of("source", "ci"));
        var local = row("a.B.run", Map.of("source", "local"));

        assertThat(ResultsFilters.byExcludedTags(List.of(ci, local), List.of("source=ci"), ResultRow::tag))
            .containsExactly(local);
    }

    @Test
    void anyExcludedPairDropsTheRow() {
        var a = row("a.B.run", Map.of("branch", "a"));
        var b = row("a.B.run", Map.of("branch", "b"));
        var c = row("a.B.run", Map.of("branch", "c"));

        assertThat(ResultsFilters.byExcludedTags(List.of(a, b, c), List.of("branch=a", "branch=b"), ResultRow::tag))
            .containsExactly(c);
    }
}
