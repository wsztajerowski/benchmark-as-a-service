package pl.wsztajerowski.baas.results;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public class ResultsQueryService implements AutoCloseable {

    private static final String COLLECTION = "jmh_benchmarks";

    private final MongoClient client;
    private final MongoDatabase db;

    public ResultsQueryService(String connectionString) {
        var cs = new com.mongodb.ConnectionString(connectionString);
        String dbName = cs.getDatabase();
        if (dbName == null || dbName.isEmpty()) {
            throw new IllegalArgumentException("Connection string must include a database name");
        }
        this.client = MongoClients.create(connectionString);
        this.db = client.getDatabase(dbName);
    }

    public List<ResultRow> queryByRequestId(String requestId) {
        MongoCollection<Document> col = db.getCollection(COLLECTION);
        var filter = Filters.eq("_id.requestId", requestId);
        return toRows(col.find(filter));
    }

    public List<ResultRow> queryByBenchmarkName(String nameRegex) {
        MongoCollection<Document> col = db.getCollection(COLLECTION);
        var filter = Filters.regex("_id.benchmarkName", Pattern.compile(nameRegex));
        return toRows(col.find(filter));
    }

    public List<ResultRow> queryByBranch(String branch) {
        MongoCollection<Document> col = db.getCollection(COLLECTION);
        var filter = Filters.eq("benchmarkMetadata.tags.branch", branch);
        return toRows(col.find(filter));
    }

    public List<ResultRow> queryAll() {
        MongoCollection<Document> col = db.getCollection(COLLECTION);
        return toRows(col.find());
    }

    private List<ResultRow> toRows(Iterable<Document> docs) {
        List<ResultRow> rows = new ArrayList<>();
        for (Document doc : docs) {
            Document id = doc.get("_id", Document.class);
            Document jmhResult = doc.get("jmhResult", Document.class);
            Document metadata = doc.get("benchmarkMetadata", Document.class);
            if (id == null || jmhResult == null) continue;

            Document primaryMetric = jmhResult.get("primaryMetric", Document.class);
            double score = primaryMetric != null ? primaryMetric.getDouble("score") : 0;
            double scoreError = primaryMetric != null ? primaryMetric.getDouble("scoreError") : 0;
            String scoreUnit = primaryMetric != null ? primaryMetric.getString("scoreUnit") : "";

            String createdAt = metadata != null && metadata.get("createdAt") != null
                ? metadata.get("createdAt").toString() : "";

            Document tags = metadata != null ? metadata.get("tags", Document.class) : null;

            rows.add(new ResultRow(
                id.getString("requestId"),
                id.getString("benchmarkName"),
                id.getString("benchmarkType"),
                jmhResult.getString("mode"),
                score,
                scoreError,
                scoreUnit,
                createdAt,
                tag(tags, "imageVersion"),
                tag(tags, "instanceType")
            ));
        }
        return rows;
    }

    /** Command payload, so stdout rather than the logger — see {@code ResultsCommand#printJson}. */
    public void printTable(List<ResultRow> rows) {
        if (rows.isEmpty()) {
            System.out.println("No results found.");
            return;
        }
        String fmt = "%-45s %-18s %-14s %-8s %14s %12s %-10s%n";
        System.out.printf(fmt, "BENCHMARK", "REQUEST_ID", "TYPE", "MODE", "SCORE", "±ERROR", "UNIT");
        System.out.println("-".repeat(130));
        for (ResultRow r : rows) {
            String shortName = r.benchmarkName().contains(".")
                ? r.benchmarkName().substring(r.benchmarkName().lastIndexOf('.') + 1)
                : r.benchmarkName();
            System.out.printf(fmt,
                truncate(shortName, 44),
                truncate(r.requestId(), 17),
                truncate(r.benchmarkType(), 13),
                r.mode() != null ? r.mode() : "",
                String.format("%.3f", r.score()),
                String.format("%.3f", r.scoreError()),
                r.scoreUnit() != null ? r.scoreUnit() : "");
        }
        environmentWarning(rows).ifPresent(System.out::println);
    }

    /**
     * Tier 1 of the environment comparison: rows that disagree on {@code imageVersion} or
     * {@code instanceType} are not comparable, and the numbers above give no hint of it.
     *
     * <p>Reported, never filtered — dropping a row would hide the very thing worth knowing, and
     * the operator is the one who decides whether the difference matters. Rows carrying no tag at
     * all predate this change and are ignored rather than counted as a difference.
     */
    static Optional<String> environmentWarning(List<ResultRow> rows) {
        var imageVersions = distinctTagValues(rows, ResultRow::imageVersion);
        var instanceTypes = distinctTagValues(rows, ResultRow::instanceType);

        var lines = new ArrayList<String>();
        if (imageVersions.size() > 1) {
            lines.add("These rows span runner image versions: " + String.join(", ", imageVersions));
        }
        if (instanceTypes.size() > 1) {
            lines.add("These rows span instance types: " + String.join(", ", instanceTypes));
        }
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        lines.addFirst("");
        lines.add("They did not all measure the same environment. Compare two of them with:");
        lines.add("  baas env diff <resultPathA> <resultPathB>");
        return Optional.of(String.join(System.lineSeparator(), lines));
    }

    private static java.util.SortedSet<String> distinctTagValues(
        List<ResultRow> rows, java.util.function.Function<ResultRow, String> tag) {
        return rows.stream()
            .map(tag)
            .filter(value -> value != null && !value.isEmpty())
            .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
    }

    /** Tags are a free-form map, so a missing one is normal rather than an error. */
    private static String tag(Document tags, String key) {
        return tags != null ? tags.getString(key) : null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    @Override
    public void close() {
        client.close();
    }
}
