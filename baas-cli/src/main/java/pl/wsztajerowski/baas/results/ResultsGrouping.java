package pl.wsztajerowski.baas.results;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Groups by {@code (project, benchmark, params, <group tag>)} and keeps the highest-scoring row per group — the
 * behaviour the retired {@code benchmark_overview.sh} had, carried forward.
 */
public final class ResultsGrouping {

    /**
     * Rows carrying no value for the grouping tag land here rather than being dropped. The tag is
     * optional on every measurement, so discarding them would quietly delete all pre-change history
     * from a default invocation.
     */
    static final String UNTAGGED = "(untagged)";

    private ResultsGrouping() {}

    public static List<ResultRow> bestPerGroup(List<ResultRow> rows, String groupTag) {
        Map<String, ResultRow> best = new LinkedHashMap<>();
        for (ResultRow row : rows) {
            // NUL-separated, a character no name can contain. Project first: under --all-projects
            // two projects can share a benchmark name, and merging them would report one project's
            // score as the other's. (Written as an escape: a literal NUL made git treat this file
            // as binary.) Params next: a sweep's variants are different workloads, so the best of
            // them is only ever the cheapest one — compared across branches it says nothing.
            String key = row.project() + "\u0000" + row.benchmarkName() + "\u0000" + row.paramsKey()
                + "\u0000" + groupValue(row, groupTag);
            best.merge(key, row, ResultsGrouping::higherScoring);
        }
        return List.copyOf(best.values());
    }

    /**
     * A non-finite score never wins. JMH reports {@code NaN} for a single-iteration run, and
     * {@code NaN} compares false against everything — so a naive {@code >} would let whichever row
     * happened to arrive first survive, making the output depend on scan order.
     */
    private static ResultRow higherScoring(ResultRow current, ResultRow candidate) {
        if (!Double.isFinite(candidate.score())) {
            return current;
        }
        if (!Double.isFinite(current.score())) {
            return candidate;
        }
        return candidate.score() > current.score() ? candidate : current;
    }

    private static String groupValue(ResultRow row, String groupTag) {
        String value = row.tag(groupTag);
        return value == null || value.isBlank() ? UNTAGGED : value;
    }

    /** Stable output regardless of the order DynamoDB returned pages in. */
    public static List<ResultRow> sortedForDisplay(List<ResultRow> rows) {
        return rows.stream()
            .sorted(Comparator.comparing((ResultRow row) -> row.project() == null ? "" : row.project())
                .thenComparing(ResultRow::benchmarkName)
                // Keeps each variant's rows together, whatever --group-by splits them by.
                .thenComparing(ResultRow::paramsKey)
                .thenComparing(row -> row.createdAt() == null ? "" : row.createdAt()))
            .toList();
    }
}
