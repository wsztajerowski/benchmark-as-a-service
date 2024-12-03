package pl.wsztajerowski.infra;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import dev.morphia.Datastore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

import static dev.morphia.Morphia.createDatastore;
import static java.util.Objects.requireNonNull;

public class DatabaseServiceBuilder {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseServiceBuilder.class);
    private URI connectionString;

    private DatabaseServiceBuilder(){
    }

    public static DatabaseServiceBuilder getMorphiaServiceBuilder(){
        return new DatabaseServiceBuilder();
    }

    public DatabaseServiceBuilder withConnectionString(URI connectionString){
        this.connectionString = connectionString;
        return this;
    }

    public DatabaseService build() {
        if (connectionString == null || connectionString.toString().isEmpty()) {
            logger.info("Using No operational database service");
            return new NoOpDatabaseService();
        }
        ConnectionString typedConnectionString = new ConnectionString(connectionString.toString());
        String database = typedConnectionString.getDatabase();
        requireNonNull(database, "Connection string has to contain database name! Please provide connection string in form: mongodb://server:port/database_name");
        MongoClient mongoClient = MongoClients
            .create(typedConnectionString);
        Datastore datastore = createDatastore(mongoClient, database);
        datastore
            .getMapper()
            .mapPackage("pl.wsztajerowski.entities");
        logger.info("Using MongoDB database service - working database: {}", database);
        return new DocumentDbService(datastore);
    }
}
