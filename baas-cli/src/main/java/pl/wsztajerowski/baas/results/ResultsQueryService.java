package pl.wsztajerowski.baas.results;

import pl.wsztajerowski.baas.model.MeasurementItemMapper;
import pl.wsztajerowski.baas.model.ResultKeys;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Reads measurements from the results table. One project is one {@code Query} on its partition, one
 * run is one {@code Query} on the request-ID index, and every other filter is applied to the rows
 * those return.
 *
 * <p>A {@code Scan} is issued only where every partition is genuinely needed — listing projects for
 * the picker, and {@code --all-projects} — because no index spans projects. It is billed by data read,
 * so it costs what the table weighs, and it grows with history; a named project never pays it.
 */
public class ResultsQueryService implements AutoCloseable {

    /**
     * Excluded rows are dropped server-side so they never enter the result set or count against the
     * page budget. A row whose {@code tags} map is absent entirely — every measurement carrying no
     * tags at all — must still come back, hence the {@code attribute_not_exists} arm.
     *
     * <p>Applied to the project sweep only; see {@link #queryByRequestId}.
     */
    private static final String EXCLUDE_FILTER =
        "attribute_not_exists(#tags) OR attribute_not_exists(#tags.#excluded) OR #tags.#excluded <> :excluded";

    private static final String TAGS_ATTRIBUTE = "tags";
    private static final String PROJECT_ATTRIBUTE = "project";
    static final String EXCLUDE_FROM_RESULTS = "exclude_from_results";
    static final String EXCLUDED_VALUE = "true";

    private final DynamoDbClient client;
    private final String tableName;

    public ResultsQueryService(DynamoDbClient client, String tableName) {
        this.client = client;
        this.tableName = tableName;
    }

    /** One project's sweep, without its excluded rows. */
    public List<ResultRow> queryProject(String project) {
        return queryProject(project, false);
    }

    /**
     * One project's sweep. {@code includeExcluded} is {@code --all-runs}. When it drops the filter
     * it drops the filter's names and values too: DynamoDB rejects a request carrying an expression
     * name or value no expression uses (see {@link #queryByRequestId}).
     */
    public List<ResultRow> queryProject(String project, boolean includeExcluded) {
        var names = new HashMap<String, String>(Map.of("#pk", MeasurementItemMapper.PK));
        var values = new HashMap<String, AttributeValue>(
            Map.of(":pk", AttributeValue.fromS(ResultKeys.partitionKey(project))));
        var request = QueryRequest.builder()
            .tableName(tableName)
            .keyConditionExpression("#pk = :pk");
        if (!includeExcluded) {
            addExcludeFilter(names, values);
            request.filterExpression(EXCLUDE_FILTER);
        }
        return runQuery(request.expressionAttributeNames(names).expressionAttributeValues(values).build());
    }

    /** Every project's measurements: {@code --all-projects}. One paginated {@code Scan}. */
    public List<ResultRow> scanAllProjects(boolean includeExcluded) {
        var request = ScanRequest.builder().tableName(tableName);
        if (!includeExcluded) {
            var names = new HashMap<String, String>();
            var values = new HashMap<String, AttributeValue>();
            addExcludeFilter(names, values);
            request.filterExpression(EXCLUDE_FILTER).expressionAttributeNames(names).expressionAttributeValues(values);
        }
        List<ResultRow> rows = new ArrayList<>();
        for (var page : client.scanPaginator(request.build())) {
            for (Map<String, AttributeValue> item : page.items()) {
                rows.add(ResultRow.from(MeasurementItemMapper.fromItem(item)));
            }
        }
        return rows;
    }

    /**
     * Projects holding at least one measurement not tagged for exclusion, sorted — what the project
     * picker offers. A project whose every row is excluded (a fixture project CI writes to) would
     * open onto an empty table, so it is left out; {@code --project <name> --all-runs} still reaches it.
     *
     * <p>Projects only the two attributes the decision needs. That trims the response, not the bill:
     * a {@code Scan} is charged for the items it reads, whatever it returns.
     */
    public List<String> listVisibleProjects() {
        var names = new HashMap<String, String>(Map.of("#project", PROJECT_ATTRIBUTE));
        var values = new HashMap<String, AttributeValue>();
        addExcludeFilter(names, values);
        var request = ScanRequest.builder()
            .tableName(tableName)
            .projectionExpression("#project")
            .filterExpression(EXCLUDE_FILTER)
            .expressionAttributeNames(names)
            .expressionAttributeValues(values)
            .build();
        var projects = new TreeSet<String>();
        for (var page : client.scanPaginator(request)) {
            for (Map<String, AttributeValue> item : page.items()) {
                AttributeValue project = item.get(PROJECT_ATTRIBUTE);
                if (project != null && project.s() != null && !project.s().isBlank()) {
                    projects.add(project.s());
                }
            }
        }
        return List.copyOf(projects);
    }

    private static void addExcludeFilter(Map<String, String> names, Map<String, AttributeValue> values) {
        names.put("#tags", TAGS_ATTRIBUTE);
        names.put("#excluded", EXCLUDE_FROM_RESULTS);
        values.put(":excluded", AttributeValue.fromS(EXCLUDED_VALUE));
    }

    /**
     * {@code requestId} sits at the tail of the base sort key, so it is the one pattern the table's
     * own key cannot reach — hence the index.
     *
     * <p>Deliberately carries no exclusion filter. Exclusion is a property of a project sweep;
     * naming one run by its id is a request for <em>that run</em>, not a query over the project's
     * history. Without this a run tagged {@code exclude_from_results=true} is invisible to every
     * assertion surface except {@code baas download} — and {@link
     * pl.wsztajerowski.baas.commands.RunCommand}'s own post-run summary, which calls this method,
     * prints empty after a <em>successful</em> excluded run.
     *
     * <p>The filter's {@code #tags}, {@code #excluded} and {@code :excluded} entries go with it,
     * and must: DynamoDB rejects a request whose expression names or values are unused by any
     * expression, so leaving them behind fails <em>every</em> {@code --request-id} query at runtime
     * with a {@code ValidationException}. A builder-level unit test would not see it; the coverage
     * is an integration test that round-trips against a real endpoint.
     */
    public List<ResultRow> queryByRequestId(String requestId) {
        return runQuery(QueryRequest.builder()
            .tableName(tableName)
            .indexName(ResultKeys.REQUEST_ID_INDEX_NAME)
            .keyConditionExpression("#pk = :pk")
            .expressionAttributeNames(Map.of("#pk", MeasurementItemMapper.GSI1PK))
            .expressionAttributeValues(Map.of(
                ":pk", AttributeValue.fromS(ResultKeys.requestIndexPartitionKey(requestId))))
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

    @Override
    public void close() {
        client.close();
    }
}
