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
     * {@code --exclude-tag k=v}, repeatable: a row matching <em>any</em> pair is dropped — the mirror of
     * {@code --tag}, where a row must match all. A list, not a map, so {@code branch=a} and
     * {@code branch=b} can both be given.
     */
    public static <R> List<R> byExcludedTags(List<R> rows, List<String> pairs,
                                              java.util.function.BiFunction<R, String, String> tagOf) {
        if (pairs == null || pairs.isEmpty()) {
            return rows;
        }
        List<String[]> parsed = pairs.stream().map(ResultsFilters::pair).toList();
        return rows.stream()
            .filter(row -> parsed.stream().noneMatch(kv -> kv[1].equals(tagOf.apply(row, kv[0]))))
            .toList();
    }

    /** A {@code key=value} pair, both sides non-empty; anything else is a usage error. */
    public static String[] pair(String text) {
        int eq = text == null ? -1 : text.indexOf('=');
        if (eq <= 0 || eq == text.length() - 1) {
            throw new IllegalArgumentException("Expected key=value, got '" + text + "'.");
        }
        return new String[]{text.substring(0, eq), text.substring(eq + 1)};
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
