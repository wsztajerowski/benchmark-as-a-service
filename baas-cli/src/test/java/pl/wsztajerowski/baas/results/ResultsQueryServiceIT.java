package pl.wsztajerowski.baas.results;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import pl.wsztajerowski.baas.model.MeasurementItemMapper;
import pl.wsztajerowski.baas.model.MeasurementKind;
import pl.wsztajerowski.baas.model.ResultKeys;
import pl.wsztajerowski.baas.model.RunItem;
import pl.wsztajerowski.baas.model.RunItemMapper;
import pl.wsztajerowski.baas.model.RunStatus;
import pl.wsztajerowski.baas.model.StoredMeasurement;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers every access path against a real DynamoDB: the project partition, the request-ID index,
 * and the two scans (every project, and the picker's project list). Both are exercised end to end through {@code MeasurementItemMapper}, so a key-encoding
 * mistake shows up as a missing row here rather than in production.
 */
@Testcontainers(disabledWithoutDocker = true)
class ResultsQueryServiceIT {

    @Container
    private static final LocalStackContainer LOCAL_STACK =
        new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.14.0"))
            .withServices(LocalStackContainer.Service.DYNAMODB);

    private DynamoDbClient client;
    private String tableName;
    private ResultsQueryService service;

    @BeforeEach
    void createTable() {
        client = DynamoDbClient.builder()
            .endpointOverride(LOCAL_STACK.getEndpoint())
            .region(Region.of(LOCAL_STACK.getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(LOCAL_STACK.getAccessKey(), LOCAL_STACK.getSecretKey())))
            .build();

        tableName = "baas-test-results-" + UUID.randomUUID();
        client.createTable(r -> r
            .tableName(tableName)
            .billingMode(BillingMode.PAY_PER_REQUEST)
            .attributeDefinitions(
                stringAttribute(MeasurementItemMapper.PK),
                stringAttribute(MeasurementItemMapper.SK),
                stringAttribute(MeasurementItemMapper.GSI1PK),
                stringAttribute(MeasurementItemMapper.GSI1SK))
            .keySchema(
                key(MeasurementItemMapper.PK, KeyType.HASH),
                key(MeasurementItemMapper.SK, KeyType.RANGE))
            .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                .indexName(ResultKeys.REQUEST_ID_INDEX_NAME)
                .keySchema(
                    key(MeasurementItemMapper.GSI1PK, KeyType.HASH),
                    key(MeasurementItemMapper.GSI1SK, KeyType.RANGE))
                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                .build()));

        service = new ResultsQueryService(client, tableName);
    }

    @Test
    void theProjectPartitionQueryReturnsThatProjectsRowsOnly() {
        put(measurement("lynx-journal", "req-1", "methodOne", Map.of()));
        put(measurement("lynx-journal", "req-1", "methodTwo", Map.of()));
        put(measurement("other-project", "req-2", "methodThree", Map.of()));

        var rows = service.queryProject("lynx-journal");

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(ResultRow::benchmarkName)
            .allSatisfy(name -> assertThat(name).startsWith("com.example.Bench"));
    }

    @Test
    void theRequestIdIndexReturnsEveryMeasurementOfOneRun() {
        put(measurement("lynx-journal", "req-1", "methodOne", Map.of()));
        put(measurement("lynx-journal", "req-1", "methodTwo", Map.of()));
        put(measurement("lynx-journal", "req-1", "methodThree", Map.of()));
        put(measurement("lynx-journal", "req-2", "methodOther", Map.of()));

        assertThat(service.queryByRequestId("req-1")).hasSize(3);
    }

    @Test
    void anUnknownRequestIdReturnsNothingRatherThanFailing() {
        put(measurement("lynx-journal", "req-1", "methodOne", Map.of()));

        assertThat(service.queryByRequestId("no-such-run")).isEmpty();
    }

    @Test
    void excludedRowsAreDroppedServerSide() {
        put(measurement("lynx-journal", "req-1", "kept", Map.of()));
        put(measurement("lynx-journal", "req-2", "dropped",
            Map.of(ResultsQueryService.EXCLUDE_FROM_RESULTS, "true")));

        var rows = service.queryProject("lynx-journal");

        assertThat(rows).singleElement()
            .extracting(ResultRow::benchmarkName)
            .isEqualTo("com.example.Bench.kept");
    }

    @Test
    void aRowCarryingNoTagsAtAllSurvivesTheExcludeFilter() {
        put(measurement("lynx-journal", "req-1", "untagged", Map.of()));

        assertThat(service.queryProject("lynx-journal"))
            .as("an absent tags map must not be read as exclude_from_results=true")
            .hasSize(1);
    }

    /**
     * Exclusion is a property of a project sweep, not of every query: naming a run by its id is a
     * request for that run. Without this the project's own CI self-test — which tags itself
     * excluded because it measures fixture code — is invisible to every assertion surface but
     * {@code baas download}.
     */
    @Test
    void anExplicitRunLookupReturnsAnExcludedRun() {
        put(measurement("lynx-journal", "req-excluded", "selfTest",
            Map.of(ResultsQueryService.EXCLUDE_FROM_RESULTS, "true")));

        assertThat(service.queryByRequestId("req-excluded"))
            .singleElement()
            .extracting(ResultRow::benchmarkName)
            .isEqualTo("com.example.Bench.selfTest");
    }

    /**
     * What 2.5 is really about. {@code RunCommand.showResults} fetches by run id and prints the
     * table; before this change it printed "No results found." after a <em>successful</em> excluded
     * run, because the fetch itself dropped the rows. Asserting on the rendered summary rather than
     * only on the row count is what pins the user-visible half of that.
     */
    @Test
    void thePostRunSummaryOfAnExcludedRunIsNotEmpty() {
        put(measurement("lynx-journal", "req-excluded", "selfTest",
            Map.of(ResultsQueryService.EXCLUDE_FROM_RESULTS, "true")));

        var out = new java.io.StringWriter();
        ResultsTable.print(pl.wsztajerowski.baas.console.Console.plain(new java.io.PrintWriter(out)),
            service.queryByRequestId("req-excluded"));

        assertThat(out.toString())
            .doesNotContain("No results found.")
            .contains("selfTest")
            .contains("req-excluded");
    }

    /**
     * The landmine this change was warned about: deleting the filter expression alone would leave
     * {@code #tags}, {@code #excluded} and {@code :excluded} declared but unused, and DynamoDB
     * rejects that outright — so EVERY {@code --request-id} query would fail at runtime with a
     * {@code ValidationException} while every builder-level unit test stayed green. Only a
     * round-trip against a real endpoint sees it, which is why this case is an IT.
     */
    @Test
    void anOrdinaryRunLookupStillSucceedsRatherThanFailingValidation() {
        put(measurement("lynx-journal", "req-1", "methodOne", Map.of("branch", "main")));

        assertThat(service.queryByRequestId("req-1")).hasSize(1);
    }

    /**
     * The other half of the new contract: the sweep and the filters layered on it still drop an
     * excluded run. `baas results --tag` filters client-side over queryProject, so the row must
     * already be gone by the time the tag filter runs.
     */
    @Test
    void aTagFilterOverTheSweepStillOmitsAnExcludedRun() {
        put(measurement("lynx-journal", "req-1", "kept", Map.of("branch", "main")));
        put(measurement("lynx-journal", "req-2", "excluded",
            Map.of("branch", "main", ResultsQueryService.EXCLUDE_FROM_RESULTS, "true")));

        var rows = ResultsFilters.byTags(service.queryProject("lynx-journal"),
            Map.of("branch", "main"));

        assertThat(rows).singleElement()
            .extracting(ResultRow::benchmarkName)
            .isEqualTo("com.example.Bench.kept");
    }

    /** {@code --all-runs}: the sweep keeps excluded rows, and the request still validates. */
    @Test
    void aSweepIncludingExcludedRowsReturnsThem() {
        put(measurement("lynx-journal", "req-1", "kept", Map.of()));
        put(measurement("lynx-journal", "req-2", "excluded",
            Map.of(ResultsQueryService.EXCLUDE_FROM_RESULTS, "true")));

        assertThat(service.queryProject("lynx-journal", true))
            .extracting(ResultRow::benchmarkName)
            .containsExactlyInAnyOrder("com.example.Bench.kept", "com.example.Bench.excluded");
    }

    @Test
    void everyProjectIsOneScanThatStillDropsExcludedRows() {
        put(measurement("lynx-journal", "req-1", "one", Map.of()));
        put(measurement("other-project", "req-2", "two", Map.of()));
        put(measurement("other-project", "req-3", "excluded",
            Map.of(ResultsQueryService.EXCLUDE_FROM_RESULTS, "true")));

        assertThat(service.scanAllProjects(false))
            .extracting(ResultRow::project)
            .containsExactlyInAnyOrder("lynx-journal", "other-project");
        assertThat(service.scanAllProjects(true)).hasSize(3);
    }

    /**
     * A project whose every row is excluded would open onto an empty table, so the picker leaves it
     * out. The fixture project CI writes to is exactly that.
     */
    @Test
    void thePickerListsOnlyProjectsWithAVisibleRow() {
        put(measurement("b-fixture", "req-1", "selfTest",
            Map.of(ResultsQueryService.EXCLUDE_FROM_RESULTS, "true")));
        put(measurement("c-real", "req-2", "one", Map.of()));
        put(measurement("a-real", "req-3", "one", Map.of()));
        put(measurement("a-real", "req-4", "two", Map.of()));

        assertThat(service.listVisibleProjects()).containsExactly("a-real", "c-real");
    }

    /**
     * A Scan page stops at 1 MB whatever matches, and the filter applies after that budget — so a
     * page can come back empty or short with more behind it. More than a page of rows proves both
     * scan paths read to exhaustion.
     */
    @Test
    void bothScansReadPastTheFirstPage() {
        String padding = "x".repeat(1000);
        var batch = new java.util.ArrayList<software.amazon.awssdk.services.dynamodb.model.WriteRequest>();
        for (int i = 0; i < 1500; i++) {
            var item = MeasurementItemMapper.toItem(measurement("noise", "req-" + i, "m" + i,
                Map.of(ResultsQueryService.EXCLUDE_FROM_RESULTS, "true", "padding", padding)));
            batch.add(software.amazon.awssdk.services.dynamodb.model.WriteRequest.builder()
                .putRequest(r -> r.item(item)).build());
            if (batch.size() == 25) {
                client.batchWriteItem(r -> r.requestItems(Map.of(tableName, List.copyOf(batch))));
                batch.clear();
            }
        }
        put(measurement("visible", "req-v", "one", Map.of()));

        assertThat(service.scanAllProjects(true)).hasSize(1501);
        assertThat(service.listVisibleProjects()).containsExactly("visible");
    }

    @Test
    void anEmptyPartitionReturnsNothing() {
        assertThat(service.queryProject("project-with-no-runs")).isEmpty();
    }

    // ─── Run items share the table (run-status-in-dynamodb) ───────────────────────

    @Test
    void everyProjectIgnoresRunItems() {
        put(measurement("lynx-journal", "req-1", "methodOne", Map.of()));
        putRun("lynx-journal", "req-1");
        putRun("only-runs", "req-9");

        assertThat(service.scanAllProjects(false)).hasSize(1);
        assertThat(service.scanAllProjects(true))
            .as("--all-runs widens exclusion, never the item kind")
            .hasSize(1);
    }

    @Test
    void thePickerDoesNotOfferAProjectThatHasOnlyRunItems() {
        put(measurement("lynx-journal", "req-1", "methodOne", Map.of()));
        putRun("only-runs", "req-9");

        assertThat(service.listVisibleProjects()).containsExactly("lynx-journal");
    }

    @Test
    void aLookupByRunIdReturnsTheMeasurementsNotTheRunItem() {
        put(measurement("lynx-journal", "req-1", "methodOne", Map.of()));
        put(measurement("lynx-journal", "req-1", "methodTwo", Map.of()));
        putRun("lynx-journal", "req-1");

        assertThat(service.queryByRequestId("req-1")).hasSize(2);
    }

    @Test
    void aRunThatStoredNothingResolvesItsPathFromTheRunItem() {
        putRun("lynx-journal", "req-failed");

        assertThat(service.resultPathForRun("req-failed")).isEqualTo("runs/lynx-journal/req-failed");
    }

    @Test
    void aRunFromBeforeRunItemsStillResolvesFromItsMeasurements() {
        put(measurement("lynx-journal", "req-old", "methodOne", Map.of()));

        assertThat(service.resultPathForRun("req-old")).isEqualTo("main/jmh/ts");
    }

    @Test
    void anUnknownRunIdResolvesToNoPath() {
        assertThat(service.resultPathForRun("no-such-run")).isNull();
    }

    private void putRun(String project, String runId) {
        var run = new RunItem(runId, project, Instant.parse("2026-10-03T00:00:00Z"),
            "runs/" + project + "/" + runId, "c5.2xlarge", RunStatus.LAUNCHING, null, null,
            Map.of("project", project), null);
        var item = new java.util.HashMap<>(RunItemMapper.key(run));
        item.putAll(RunItemMapper.identityAttributes(run));
        item.put(RunItemMapper.STATUS, software.amazon.awssdk.services.dynamodb.model.AttributeValue.fromS(run.status()));
        client.putItem(PutItemRequest.builder().tableName(tableName).item(item).build());
    }

    private void put(StoredMeasurement measurement) {
        client.putItem(PutItemRequest.builder()
            .tableName(tableName)
            .item(MeasurementItemMapper.toItem(measurement))
            .build());
    }

    private static StoredMeasurement measurement(
        String project, String requestId, String method, Map<String, String> tags) {
        return new StoredMeasurement(
            project, requestId, Instant.parse("2026-08-19T09:00:00.000Z"), MeasurementKind.JMH,
            "com.example.Bench", method, "thrpt", Map.of(), 1234.5, 1.0, "ops/s",
            Map.of(), null, tags,
            "main/jmh/ts", "main/jmh/ts/jmh-result.json", "main/jmh/ts/environment.json", null);
    }

    private static AttributeDefinition stringAttribute(String name) {
        return AttributeDefinition.builder()
            .attributeName(name).attributeType(ScalarAttributeType.S).build();
    }

    private static KeySchemaElement key(String name, KeyType type) {
        return KeySchemaElement.builder().attributeName(name).keyType(type).build();
    }
}
