package pl.wsztajerowski.baas.results;

import pl.wsztajerowski.baas.model.MeasurementItemMapper;
import pl.wsztajerowski.baas.model.ResultKeys;
import pl.wsztajerowski.baas.model.JobItem;
import pl.wsztajerowski.baas.jobs.DynamoDbJobRecorder;
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
 * job is one {@code Query} on the job-ID index, and every other filter is applied to the rows
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
     * <p>Applied to the project sweep only; see {@link #queryByJobId}.
     */
    private static final String EXCLUDE_FILTER =
        "attribute_not_exists(#tags) OR attribute_not_exists(#tags.#excluded) OR #tags.#excluded <> :excluded";

    /**
     * The table holds job items too, at {@code pk = JOB}. A {@code Scan} sees every partition, so
     * each one restricts itself to measurements; {@link MeasurementItemMapper#fromItem} refuses
     * anything else, so a reader that forgets fails loudly.
     */
    private static final String MEASUREMENTS_ONLY = "begins_with(#pk, :resultPrefix)";

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
     * One project's sweep. {@code includeExcluded} is {@code --all-jobs}. When it drops the filter
     * it drops the filter's names and values too: DynamoDB rejects a request carrying an expression
     * name or value no expression uses (see {@link #queryByJobId}).
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
        var names = new HashMap<String, String>();
        var values = new HashMap<String, AttributeValue>();
        addMeasurementsOnlyFilter(names, values);
        String filter = MEASUREMENTS_ONLY;
        if (!includeExcluded) {
            addExcludeFilter(names, values);
            filter = MEASUREMENTS_ONLY + " AND (" + EXCLUDE_FILTER + ")";
        }
        var request = ScanRequest.builder()
            .tableName(tableName)
            .filterExpression(filter)
            .expressionAttributeNames(names)
            .expressionAttributeValues(values);
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
     * open onto an empty table, so it is left out; {@code --project <name> --all-jobs} still reaches it.
     *
     * <p>Projects only the two attributes the decision needs. That trims the response, not the bill:
     * a {@code Scan} is charged for the items it reads, whatever it returns.
     */
    public List<String> listVisibleProjects() {
        var names = new HashMap<String, String>(Map.of("#project", PROJECT_ATTRIBUTE));
        var values = new HashMap<String, AttributeValue>();
        addMeasurementsOnlyFilter(names, values);
        addExcludeFilter(names, values);
        var request = ScanRequest.builder()
            .tableName(tableName)
            .projectionExpression("#project")
            // Without the pk test, a job item's project attribute would offer a project whose
            // only jobs stored nothing — and open onto an empty table.
            .filterExpression(MEASUREMENTS_ONLY + " AND (" + EXCLUDE_FILTER + ")")
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

    private static void addMeasurementsOnlyFilter(Map<String, String> names, Map<String, AttributeValue> values) {
        names.put("#pk", MeasurementItemMapper.PK);
        values.put(":resultPrefix", AttributeValue.fromS(ResultKeys.PK_PREFIX));
    }

    private static void addExcludeFilter(Map<String, String> names, Map<String, AttributeValue> values) {
        names.put("#tags", TAGS_ATTRIBUTE);
        names.put("#excluded", EXCLUDE_FROM_RESULTS);
        values.put(":excluded", AttributeValue.fromS(EXCLUDED_VALUE));
    }

    /**
     * {@code jobId} sits at the tail of the base sort key, so it is the one pattern the table's
     * own key cannot reach — hence the index.
     *
     * <p>Deliberately carries no exclusion filter. Exclusion is a property of a project sweep;
     * naming one job by its id is a request for <em>that job</em>, not a query over the project's
     * history. Without this a job tagged {@code exclude_from_results=true} is invisible to every
     * assertion surface except {@code baas download} — and {@link
     * pl.wsztajerowski.baas.commands.RunCommand}'s own post-job summary, which calls this method,
     * prints empty after a <em>successful</em> excluded job.
     *
     * <p>The filter's {@code #tags}, {@code #excluded} and {@code :excluded} entries go with it,
     * and must: DynamoDB rejects a request whose expression names or values are unused by any
     * expression, so leaving them behind fails <em>every</em> {@code --job-id} query at runtime
     * with a {@code ValidationException}. A builder-level unit test would not see it; the coverage
     * is an integration test that round-trips against a real endpoint.
     *
     * <p>The job's own job item shares its index partition and is filtered out. A key condition
     * cannot say "not equal", and DynamoDB refuses a filter on a key attribute ({@code gsi1sk} is
     * the index's sort key), so the filter keys on {@code kind}: every measurement carries it, and
     * a job item never does.
     */
    public List<ResultRow> queryByJobId(String jobId) {
        return runQuery(QueryRequest.builder()
            .tableName(tableName)
            .indexName(ResultKeys.JOB_ID_INDEX_NAME)
            .keyConditionExpression("#pk = :pk")
            .filterExpression("attribute_exists(#kind)")
            .expressionAttributeNames(Map.of("#pk", MeasurementItemMapper.GSI1PK, "#kind", MeasurementItemMapper.KIND))
            .expressionAttributeValues(Map.of(
                ":pk", AttributeValue.fromS(ResultKeys.jobIndexPartitionKey(jobId))))
            .build());
    }

    /**
     * The S3 prefix a job's artifacts were written to, or {@code null} when the index holds no such
     * job. Read from the stored attribute rather than reconstructed, which is what keeps every
     * historical path resolving after the layout changed — and why nothing needs a compatibility
     * shim. {@code jobId-index} projects ALL, so each lookup is one query and no GetItem.
     *
     * <p>The job item is asked first, addressed exactly by {@code gsi1sk = JOB}: it exists for every
     * job since job items did, including one that failed or never launched and so stored no
     * measurement. A job from before that has only measurements, which the second query reads.
     */
    public String resultPathForJob(String jobId) {
        var fromJobItem = new DynamoDbJobRecorder(client, tableName).find(jobId)
            .map(JobItem::resultPath);
        if (fromJobItem.isPresent()) {
            return fromJobItem.get();
        }
        // No job item: a job recorded before job items existed. Its measurements are the only
        // index entries under its id, so the first one names the path.
        var response = client.query(QueryRequest.builder()
            .tableName(tableName)
            .indexName(ResultKeys.JOB_ID_INDEX_NAME)
            .keyConditionExpression("#pk = :pk")
            .expressionAttributeNames(Map.of("#pk", MeasurementItemMapper.GSI1PK))
            .expressionAttributeValues(Map.of(
                ":pk", AttributeValue.fromS(ResultKeys.jobIndexPartitionKey(jobId))))
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
