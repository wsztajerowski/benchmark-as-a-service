package pl.wsztajerowski;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

import java.util.List;
import java.util.Map;

import static pl.wsztajerowski.baas.model.MeasurementItemMapper.GSI1PK;
import static pl.wsztajerowski.baas.model.MeasurementItemMapper.GSI1SK;
import static pl.wsztajerowski.baas.model.MeasurementItemMapper.PK;
import static pl.wsztajerowski.baas.model.MeasurementItemMapper.SK;
import static pl.wsztajerowski.baas.model.ResultKeys.REQUEST_ID_INDEX_NAME;

/**
 * Adds a results table to the shared LocalStack container. Extends the S3 base rather than starting
 * its own container, so tests needing both a bucket and a table get them from one instance.
 *
 * <p>The table is created per test from the same key names the production mapper uses, so a rename
 * there fails to compile here rather than yielding a table that quietly matches nothing. The schema
 * must stay in step with {@code ResultsTable} in {@code infra/cf-template-core.yaml}.
 */
public class TestcontainersWithDynamoDbBaseIT extends TestcontainersWithS3BaseIT {
    protected static final String TEST_TABLE_NAME = "test-results";

    protected DynamoDbClient dynamoDbClient;

    @BeforeEach
    void createTableAndDynamoDbClient() {
        dynamoDbClient = DynamoDbClient
            .builder()
            .endpointOverride(LOCAL_STACK_CONTAINER.getEndpoint())
            .region(Region.of(LOCAL_STACK_CONTAINER.getRegion()))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(LOCAL_STACK_CONTAINER.getAccessKey(), LOCAL_STACK_CONTAINER.getSecretKey())
                )
            )
            .build();

        dynamoDbClient.createTable(CreateTableRequest.builder()
            .tableName(TEST_TABLE_NAME)
            .billingMode(BillingMode.PAY_PER_REQUEST)
            .attributeDefinitions(
                stringAttribute(PK),
                stringAttribute(SK),
                stringAttribute(GSI1PK),
                stringAttribute(GSI1SK))
            .keySchema(
                key(PK, KeyType.HASH),
                key(SK, KeyType.RANGE))
            .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                .indexName(REQUEST_ID_INDEX_NAME)
                .keySchema(
                    key(GSI1PK, KeyType.HASH),
                    key(GSI1SK, KeyType.RANGE))
                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                .build())
            .build());
    }

    @AfterEach
    void dropTable() {
        dynamoDbClient.deleteTable(DeleteTableRequest.builder().tableName(TEST_TABLE_NAME).build());
    }

    /** Production code never scans; a test may, and it is the cheapest way to see every item. */
    protected List<Map<String, AttributeValue>> scanTestTable() {
        return dynamoDbClient.scan(ScanRequest.builder().tableName(TEST_TABLE_NAME).build()).items();
    }

    protected long countItemsInTestTable() {
        return scanTestTable().size();
    }

    private static AttributeDefinition stringAttribute(String name) {
        return AttributeDefinition.builder()
            .attributeName(name)
            .attributeType(ScalarAttributeType.S)
            .build();
    }

    private static KeySchemaElement key(String name, KeyType type) {
        return KeySchemaElement.builder().attributeName(name).keyType(type).build();
    }
}
