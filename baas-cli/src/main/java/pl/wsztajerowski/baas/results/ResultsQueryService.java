package pl.wsztajerowski.baas.results;

import pl.wsztajerowski.baas.model.MeasurementItemMapper;
import pl.wsztajerowski.baas.model.ResultKeys;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads measurements from the results table. Two access paths and no more: one {@code Query} on the
 * project partition, and one on the request-ID index. Every other filter is applied to the rows
 * those return.
 *
 * <p>Never issues a {@code Scan}. The operator role is not granted one, so an accidental scan fails
 * with an access error rather than quietly billing a full-table read.
 */
public class ResultsQueryService implements AutoCloseable {

    /**
     * Excluded rows are dropped server-side so they never enter the result set or count against the
     * page budget. A row whose {@code tags} map is absent entirely — every measurement carrying no
     * tags at all — must still come back, hence the {@code attribute_not_exists} arm.
     */
    private static final String EXCLUDE_FILTER =
        "attribute_not_exists(#tags) OR attribute_not_exists(#tags.#excluded) OR #tags.#excluded <> :excluded";

    private static final String TAGS_ATTRIBUTE = "tags";
    static final String EXCLUDE_FROM_RESULTS = "exclude_from_results";

    private final DynamoDbClient client;
    private final String tableName;

    public ResultsQueryService(DynamoDbClient client, String tableName) {
        this.client = client;
        this.tableName = tableName;
    }

    /** The single access path for everything except {@code --request-id}. */
    public List<ResultRow> queryProject(String project) {
        return runQuery(QueryRequest.builder()
            .tableName(tableName)
            .keyConditionExpression("#pk = :pk")
            .expressionAttributeNames(Map.of(
                "#pk", MeasurementItemMapper.PK,
                "#tags", TAGS_ATTRIBUTE,
                "#excluded", EXCLUDE_FROM_RESULTS))
            .expressionAttributeValues(Map.of(
                ":pk", AttributeValue.fromS(ResultKeys.partitionKey(project)),
                ":excluded", AttributeValue.fromS("true")))
            .filterExpression(EXCLUDE_FILTER)
            .build());
    }

    /**
     * {@code requestId} sits at the tail of the base sort key, so it is the one pattern the table's
     * own key cannot reach — hence the index.
     */
    public List<ResultRow> queryByRequestId(String requestId) {
        return runQuery(QueryRequest.builder()
            .tableName(tableName)
            .indexName(ResultKeys.REQUEST_ID_INDEX_NAME)
            .keyConditionExpression("#pk = :pk")
            .expressionAttributeNames(Map.of(
                "#pk", MeasurementItemMapper.GSI1PK,
                "#tags", TAGS_ATTRIBUTE,
                "#excluded", EXCLUDE_FROM_RESULTS))
            .expressionAttributeValues(Map.of(
                ":pk", AttributeValue.fromS(ResultKeys.requestIndexPartitionKey(requestId)),
                ":excluded", AttributeValue.fromS("true")))
            .filterExpression(EXCLUDE_FILTER)
            .build());
    }

    /**
     * The S3 prefix a run's artifacts were written to, or {@code null} when the index holds no such
     * run. Read from the stored attribute rather than reconstructed, which is what keeps every
     * historical path resolving after the layout changed — and why nothing needs a compatibility
     * shim. {@code requestId-index} projects ALL, so this is one query and no follow-up GetItem.
     */
    public String resultPathForRun(String runId) {
        var response = client.query(QueryRequest.builder()
            .tableName(tableName)
            .indexName(ResultKeys.REQUEST_ID_INDEX_NAME)
            .keyConditionExpression("#pk = :pk")
            .expressionAttributeNames(Map.of("#pk", MeasurementItemMapper.GSI1PK))
            .expressionAttributeValues(Map.of(
                ":pk", AttributeValue.fromS(ResultKeys.requestIndexPartitionKey(runId))))
            .limit(1)
            .build());
        return response.items().stream()
            .findFirst()
            .map(item -> MeasurementItemMapper.fromItem(item).resultPath())
            .orElse(null);
    }

    /**
     * Paginates to exhaustion. A Query returns at most 1 MB per page regardless of matches, and a
     * filter expression is applied after that budget is spent — so a single page can come back
     * empty with more results behind it, and stopping at the first page would silently truncate.
     */
    private List<ResultRow> runQuery(QueryRequest request) {
        List<ResultRow> rows = new ArrayList<>();
        for (var page : client.queryPaginator(request)) {
            for (Map<String, AttributeValue> item : page.items()) {
                rows.add(ResultRow.from(MeasurementItemMapper.fromItem(item)));
            }
        }
        return rows;
    }

    /** Command payload, so stdout rather than the logger — see {@code ResultsCommand#printJson}. */
    public void printTable(List<ResultRow> rows) {
        if (rows.isEmpty()) {
            System.out.println("No results found.");
            return;
        }
        // 28 is RunId.LENGTH. Truncating at 17 landed inside the old <type>-<date> prefix, which
        // rendered distinct rows identically; a fixed-width id removes truncation as a question.
        String fmt = "%-45s %-28s %-14s %-8s %14s %12s %-10s%n";
        System.out.printf(fmt, "BENCHMARK", "REQUEST_ID", "TYPE", "MODE", "SCORE", "±ERROR", "UNIT");
        System.out.println("-".repeat(141));
        for (ResultRow r : rows) {
            String shortName = r.benchmarkName().contains(".")
                ? r.benchmarkName().substring(r.benchmarkName().lastIndexOf('.') + 1)
                : r.benchmarkName();
            System.out.printf(fmt,
                truncate(shortName, 44),
                r.requestId(),
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

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    @Override
    public void close() {
        client.close();
    }
}
