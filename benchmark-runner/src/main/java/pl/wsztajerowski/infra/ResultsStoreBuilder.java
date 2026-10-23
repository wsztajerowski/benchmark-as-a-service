package pl.wsztajerowski.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;

/**
 * Builds the results store: the DynamoDB table every job names.
 *
 * <p>Absent configuration is a hard failure, and nothing discards measurements. An older builder
 * returned a no-op when the connection string was null or empty, which let a paid job report
 * success while discarding its measurements — the single most expensive silent failure this
 * project had. Its explicit successor, {@code --no-database}, is gone too: a CLI job records its
 * status in the results table, so it always has one, and a local job names a LocalStack table.
 */
public class ResultsStoreBuilder {
    private static final Logger logger = LoggerFactory.getLogger(ResultsStoreBuilder.class);

    private String tableName;
    private URI dynamoDbEndpoint;
    private DynamoDbClient dynamoDbClient;

    private ResultsStoreBuilder() {
    }

    public static ResultsStoreBuilder builder() {
        return new ResultsStoreBuilder();
    }

    public ResultsStoreBuilder withTableName(String tableName) {
        this.tableName = tableName;
        return this;
    }

    public ResultsStoreBuilder withDynamoDbEndpoint(URI dynamoDbEndpoint) {
        this.dynamoDbEndpoint = dynamoDbEndpoint;
        return this;
    }

    /**
     * Supplies a ready-made client instead of constructing one. Without this, {@code build()}
     * resolves the region from the ambient environment — IMDS on the runner, {@code AWS_REGION} in
     * CI — which a test machine has neither of. Tests and LocalStack callers pass a configured
     * client here rather than the builder defaulting a region, which would mask a genuine
     * misconfiguration in production.
     */
    public ResultsStoreBuilder withDynamoDbClient(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
        return this;
    }

    public ResultsStore build() {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalStateException(
                "No results store configured. Pass --results-table (with --dynamodb-endpoint for LocalStack).");
        }
        return dynamoDbStore();
    }

    private ResultsStore dynamoDbStore() {
        logger.info("Using DynamoDB results store - table: {}", tableName);
        if (dynamoDbClient != null) {
            return new DynamoDbResultsStore(dynamoDbClient, tableName);
        }
        var clientBuilder = DynamoDbClient.builder();
        if (dynamoDbEndpoint != null) {
            clientBuilder.endpointOverride(dynamoDbEndpoint);
        }
        return new DynamoDbResultsStore(clientBuilder.build(), tableName);
    }
}
