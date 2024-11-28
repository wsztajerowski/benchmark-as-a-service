package pl.wsztajerowski.infra;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import pl.wsztajerowski.MongoDbTestHelpers;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static pl.wsztajerowski.MongoDbTestHelpers.byId;
import static pl.wsztajerowski.infra.DatabaseServiceBuilder.getMorphiaServiceBuilder;

@Testcontainers
class DatabaseServiceIT {
    private static final String TEST_DB_NAME = "morphia-service-tests";
    private static URI connectionString;
    @Container
    protected final static MongoDBContainer MONGO_DB_CONTAINER = new MongoDBContainer(DockerImageName.parse("mongo:7.0.5"))
        .withExposedPorts(27017);
    private DatabaseService sut;
    private static MongoDbTestHelpers helper;

    @BeforeAll
    static void setupConnectionStringAndDbHelper(){
        connectionString = URI.create(MONGO_DB_CONTAINER.getConnectionString()).resolve(TEST_DB_NAME);
        helper = new MongoDbTestHelpers(connectionString);
    }

    @BeforeEach
    void setupSut(){
        sut = getMorphiaServiceBuilder()
            .withConnectionString(connectionString)
            .build();
    }

    @Test
    void test_isMongoRunning() {
        assertThat(MONGO_DB_CONTAINER.isRunning())
            .isTrue();
    }

    @Test
    void read_data_saved_by_morphia_service(){
        // given
        TestEntity entity = new TestEntity("benchmarks", 23);

        // when
        sut.save(entity);

        // then
        helper.assertFindResult("test-entities", byId("benchmarks"), documents ->
            assertThat(documents.first())
                .isNotNull()
                .containsEntry("_id", "benchmarks")
                .containsEntry("quantity", 23));
    }


}