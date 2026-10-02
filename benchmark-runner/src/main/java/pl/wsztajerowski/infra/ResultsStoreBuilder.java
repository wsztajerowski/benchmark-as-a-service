package pl.wsztajerowski.infra;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.morphia.Datastore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;

import static dev.morphia.Morphia.createDatastore;
import static java.util.Objects.requireNonNull;

/**
 * Selects exactly one results store.
 *
 * <p>Absent configuration is a hard failure, and nothing discards measurements. An older builder
 * returned a no-op when the connection string was null or empty, which let a paid run report
 * success while discarding its measurements — the single most expensive silent failure this
 * project had. Its explicit successor, {@code --no-database}, is gone too: a CLI run records its
 * status in the results table, so it always has one, and a local run names a LocalStack table.
 */
public class ResultsStoreBuilder {
    private static final Logger logger = LoggerFactory.getLogger(ResultsStoreBuilder.class);

    private String tableName;
    private URI connectionString;
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

    public ResultsStoreBuilder withConnectionString(URI connectionString) {
        this.connectionString = connectionString;
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
        boolean hasTable = tableName != null && !tableName.isBlank();
        boolean hasConnectionString = connectionString != null && !connectionString.toString().isBlank();

        if (hasTable && hasConnectionString) {
            throw new IllegalStateException(
                "More than one results store selected. Name exactly one of --results-table or "
                    + "--mongo-connection-string, not both.");
        }
        if (!hasTable && !hasConnectionString) {
            throw new IllegalStateException(
                "No results store configured. Pass --results-table for DynamoDB (with "
                    + "--dynamodb-endpoint for LocalStack), or --mongo-connection-string for MongoDB.");
        }

        if (hasTable) {
            return dynamoDbStore();
        }
        return mongoStore();
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

    private ResultsStore mongoStore() {
        ConnectionString typedConnectionString = new ConnectionString(connectionString.toString());
        String database = typedConnectionString.getDatabase();
        requireNonNull(database, "Connection string has to contain database name! Please provide connection string in form: mongodb://server:port/database_name");
        MongoClient mongoClient = MongoClients
            .create(typedConnectionString);
        Datastore datastore = createDatastore(mongoClient, database);
        datastore
            .getMapper()
            .mapPackage("pl.wsztajerowski.entities");
        logger.info("Using MongoDB results store - working database: {}", database);
        return new MongoResultsStore(datastore);
    }
}
