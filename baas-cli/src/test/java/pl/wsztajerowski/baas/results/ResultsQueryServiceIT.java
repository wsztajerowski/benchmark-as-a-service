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

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the two access paths against a real DynamoDB: the project partition and the request-ID
 * index. Both are exercised end to end through {@code MeasurementItemMapper}, so a key-encoding
 * mistake shows up as a missing row here rather than in production.
 */
@Testcontainers(disabledWithoutDocker = true)
class ResultsQueryServiceIT {

    @Container
    private static final LocalStackContainer LOCAL_STACK =
        new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8"))
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

    @Test
    void anEmptyPartitionReturnsNothing() {
        assertThat(service.queryProject("project-with-no-runs")).isEmpty();
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
            "com.example.Bench", method, "thrpt", 1234.5, 1.0, "ops/s",
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
