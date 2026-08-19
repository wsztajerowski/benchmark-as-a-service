package pl.wsztajerowski.baas.results;

import pl.wsztajerowski.baas.model.TagKeys;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Everything applied to the rows a query returned, rather than by choosing a different index.
 * Deliberately pure: the access path is fixed, so filtering has no reason to touch the client.
 */
public final class ResultsFilters {

    public static final String BRANCH = "branch";

    private ResultsFilters() {}

    /** Repeated {@code --tag} options combine conjunctively: a row must carry every one. */
    public static List<ResultRow> byTags(List<ResultRow> rows, Map<String, String> required) {
        if (required == null || required.isEmpty()) {
            return rows;
        }
        return rows.stream()
            .filter(row -> required.entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(row.tag(entry.getKey()))))
            .toList();
    }

    /**
     * A regular expression, found anywhere in the name — {@code --benchmark-name Queue} is expected
     * to match {@code com.example.QueueBenchmark.offer}, so this is {@code find}, not {@code matches}.
     */
    public static List<ResultRow> byBenchmarkName(List<ResultRow> rows, String regex) {
        if (regex == null || regex.isBlank()) {
            return rows;
        }
        Pattern pattern = Pattern.compile(regex);
        return rows.stream()
            .filter(row -> pattern.matcher(row.benchmarkName()).find())
            .toList();
    }

    /**
     * Keeps rows whose {@code branch} tag names a branch still present in the repository. A row
     * with no {@code branch} tag is kept: the tag is optional, and dropping untagged rows would
     * make this filter silently delete all pre-change history. An empty branch list is a no-op for
     * the same reason — it means git told us nothing, not that nothing is alive.
     */
    public static List<ResultRow> byLivingBranches(List<ResultRow> rows, List<String> livingBranches) {
        if (livingBranches == null || livingBranches.isEmpty()) {
            return rows;
        }
        return rows.stream()
            .filter(row -> {
                String branch = row.tag(BRANCH);
                return branch == null || livingBranches.contains(branch);
            })
            .toList();
    }

    /**
     * Warns when a {@code --tag} key is outside the known vocabulary AND no returned row carries
     * it. Both conditions matter: a custom key that some row does carry is a legitimate filter, and
     * a known key matching nothing is an ordinary empty result. It is the combination — an unknown
     * key nothing uses — that usually means a typo, and would otherwise present as "no results".
     */
    public static Optional<String> unknownTagWarning(List<ResultRow> rows, Map<String, String> required) {
        if (required == null || required.isEmpty()) {
            return Optional.empty();
        }
        List<String> suspect = new ArrayList<>();
        for (String key : required.keySet()) {
            boolean known = TagKeys.KNOWN.contains(key) || BRANCH.equals(key);
            boolean carriedBySomeRow = rows.stream().anyMatch(row -> row.tag(key) != null);
            if (!known && !carriedBySomeRow) {
                suspect.add(key);
            }
        }
        if (suspect.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
            "No result carries the tag key(s) " + String.join(", ", suspect)
                + ", and they are not known keys (" + String.join(", ", sorted()) + "). Check for a typo.");
    }

    private static List<String> sorted() {
        return TagKeys.KNOWN.stream().sorted().toList();
    }
}
