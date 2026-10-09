package pl.wsztajerowski.baas.results;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code --best-per}: groups by {@code (project, benchmark, params, mode, <tag>)} and keeps the best
 * row per group — and the ordering every listing shares.
 */
public final class ResultsGrouping {

    /**
     * Rows carrying no value for the grouping tag land here rather than being dropped. The tag is
     * optional on every measurement, so discarding them would quietly delete history.
     */
    static final String UNTAGGED = "(untagged)";

    /**
     * JMH modes whose score is time per operation, where lower is better. Throughput — and anything
     * that is not a JMH mode, such as a JCStress row — keeps "higher is better".
     */
    static final Set<String> LOWER_IS_BETTER = Set.of("avgt", "sample", "ss");

    /** What {@code --sort-by} accepts on {@code results query}. */
    public static final List<String> SORT_FIELDS = List.of("created", "benchmark", "score", "project");

    private ResultsGrouping() {}

    public static List<ResultRow> bestPerGroup(List<ResultRow> rows, String groupTag) {
        Map<String, ResultRow> best = new LinkedHashMap<>();
        for (ResultRow row : rows) {
            // NUL-separated, a character no name can contain. Project first: under --all-projects
            // two projects can share a benchmark name. Params next: a sweep's variants are different
            // workloads. Mode next: one benchmark measured as thrpt and avgt is two numbers in two
            // units, and choosing between them compares ops/s with ns/op. (Written as an escape: a
            // literal NUL made git treat this file as binary.)
            String key = row.project() + "\u0000" + row.benchmarkName() + "\u0000" + row.paramsKey()
                + "\u0000" + row.mode() + "\u0000" + groupValue(row, groupTag);
            best.merge(key, row, ResultsGrouping::better);
        }
        return List.copyOf(best.values());
    }

    static boolean lowerIsBetter(String mode) {
        return mode != null && LOWER_IS_BETTER.contains(mode.toLowerCase(Locale.ROOT));
    }

    /**
     * A non-finite score never wins. JMH reports {@code NaN} for a single-iteration run, and
     * {@code NaN} compares false against everything — so a naive comparison would let whichever row
     * happened to arrive first survive, making the output depend on scan order.
     */
    private static ResultRow better(ResultRow current, ResultRow candidate) {
        if (!Double.isFinite(candidate.score())) {
            return current;
        }
        if (!Double.isFinite(current.score())) {
            return candidate;
        }
        boolean candidateWins = lowerIsBetter(candidate.mode())
            ? candidate.score() < current.score()
            : candidate.score() > current.score();
        return candidateWins ? candidate : current;
    }

    private static String groupValue(ResultRow row, String groupTag) {
        String value = row.tag(groupTag);
        return value == null || value.isBlank() ? UNTAGGED : value;
    }

    /**
     * The shared ordering: newest first unless {@code --sort-by} names another field, reversed by
     * {@code --asc}. Ties fall back to the newest first, then to a stable identity, so the output
     * does not depend on the order DynamoDB returned pages in.
     */
    public static List<ResultRow> sorted(List<ResultRow> rows, String field, boolean ascending) {
        Comparator<ResultRow> created = Comparator.comparing(
            (ResultRow row) -> row.createdAt() == null ? "" : row.createdAt());
        Comparator<ResultRow> primary = switch (field == null ? "created" : field) {
            case "benchmark" -> Comparator.comparing(ResultRow::benchmarkName).thenComparing(ResultRow::paramsKey);
            case "score" -> Comparator.comparingDouble(ResultRow::score);
            case "project" -> Comparator.comparing((ResultRow row) -> row.project() == null ? "" : row.project());
            default -> created;
        };
        Comparator<ResultRow> order = ascending ? primary : primary.reversed();
        if ("score".equals(field)) {
            // An unknown score is last whichever way the scores run: comparingDouble puts NaN above
            // everything, which would head the descending default with rows that measured nothing.
            order = Comparator.comparing((ResultRow row) -> !Double.isFinite(row.score())).thenComparing(order);
        }
        return rows.stream()
            .sorted(order.thenComparing(created.reversed())
                .thenComparing(row -> row.jobId() == null ? "" : row.jobId())
                .thenComparing(ResultRow::benchmarkName)
                .thenComparing(ResultRow::paramsKey))
            .toList();
    }
}
