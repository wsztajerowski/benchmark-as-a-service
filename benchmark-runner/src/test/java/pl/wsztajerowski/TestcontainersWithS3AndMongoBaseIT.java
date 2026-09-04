package pl.wsztajerowski;

import com.mongodb.MongoClient;
import com.mongodb.client.MongoClients;
import dev.morphia.Datastore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;

import static dev.morphia.Morphia.createDatastore;

public class TestcontainersWithS3AndMongoBaseIT extends TestcontainersWithS3BaseIT {
    protected static final String TEST_DB_NAME = "integration-tests";

    private static URI connectionString;
    @Container
    private final static MongoDBContainer MONGO_DB_CONTAINER = new MongoDBContainer(DockerImageName.parse("mongo:7.0.5"))
        .withEnv("MONGO_INITDB_DATABASE", TEST_DB_NAME)
        .withExposedPorts(27017);

    protected MongoClient getMongoClient(){
        return new MongoClient(MONGO_DB_CONTAINER.getConnectionString());
    }

    /**
     * A Morphia datastore over the test database, mapped the same way the production builder maps
     * it — {@code pl.wsztajerowski.entities} is auto-mapped, so an entity outside that package is
     * silently never persisted.
     */
    protected Datastore datastore() {
        Datastore datastore = createDatastore(
            MongoClients.create(MONGO_DB_CONTAINER.getConnectionString()), TEST_DB_NAME);
        datastore
            .getMapper()
            .mapPackage("pl.wsztajerowski.entities");
        return datastore;
    }

    @BeforeAll
    static void setConnectionString(){
        connectionString = URI.create(MONGO_DB_CONTAINER.getConnectionString()).resolve(TEST_DB_NAME);
    }

    @AfterEach
    void dropTestDb(){
        try(MongoClient mongoClient = getMongoClient()) {
            mongoClient
                .dropDatabase(TEST_DB_NAME);
        }
    }

    public static URI getConnectionString() {
        return connectionString;
    }
}
