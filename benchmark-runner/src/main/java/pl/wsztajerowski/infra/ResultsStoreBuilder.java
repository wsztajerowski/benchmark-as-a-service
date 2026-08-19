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
 * <p>Absent configuration is a hard failure. The previous builder returned a no-op when the
 * connection string was null or empty, which let a paid run report success while discarding its
 * measurements — the single most expensive silent failure this project had. Discarding now
 * requires naming the intent with {@code --no-database}.
 */
public class ResultsStoreBuilder {
    private static final Logger logger = LoggerFactory.getLogger(ResultsStoreBuilder.class);

    private String tableName;
    private URI connectionString;
    private boolean noDatabase;
    private URI dynamoDbEndpoint;

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

    public ResultsStoreBuilder withNoDatabase(boolean noDatabase) {
        this.noDatabase = noDatabase;
        return this;
    }

    public ResultsStoreBuilder withDynamoDbEndpoint(URI dynamoDbEndpoint) {
        this.dynamoDbEndpoint = dynamoDbEndpoint;
        return this;
    }

    public ResultsStore build() {
        boolean hasTable = tableName != null && !tableName.isBlank();
        boolean hasConnectionString = connectionString != null && !connectionString.toString().isBlank();

        int selected = (hasTable ? 1 : 0) + (hasConnectionString ? 1 : 0) + (noDatabase ? 1 : 0);
        if (selected > 1) {
            throw new IllegalStateException(
                "More than one results store selected. Name exactly one of --results-table, "
                    + "--mongo-connection-string or --no-database, not both.");
        }
        if (selected == 0) {
            throw new IllegalStateException(
                "No results store configured. Pass --results-table for DynamoDB, "
                    + "--mongo-connection-string for MongoDB, or --no-database to discard "
                    + "measurements deliberately.");
        }

        if (noDatabase) {
            return new NoOpResultsStore();
        }
        if (hasTable) {
            return dynamoDbStore();
        }
        return mongoStore();
    }

    private ResultsStore dynamoDbStore() {
        var clientBuilder = DynamoDbClient.builder();
        if (dynamoDbEndpoint != null) {
            clientBuilder.endpointOverride(dynamoDbEndpoint);
        }
        logger.info("Using DynamoDB results store - table: {}", tableName);
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
