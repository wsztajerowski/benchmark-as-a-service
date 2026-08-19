package pl.wsztajerowski.services;

import dev.morphia.annotations.Entity;
import org.json.JSONArray;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pl.wsztajerowski.MongoDbTestHelpers;
import pl.wsztajerowski.TestcontainersWithS3AndMongoBaseIT;
import pl.wsztajerowski.entities.MongoMeasurementDocument;
import pl.wsztajerowski.infra.MongoResultsStore;
import pl.wsztajerowski.infra.S3StorageService;
import pl.wsztajerowski.services.options.CommonSharedOptions;
import pl.wsztajerowski.services.options.JmhOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static pl.wsztajerowski.MongoDbTestHelpers.all;
import static pl.wsztajerowski.services.JmhSubcommandServiceBuilder.serviceBuilder;
import static pl.wsztajerowski.services.options.JmhBenchmarkOptions.jmhBenchmarkOptionsBuilder;
import static pl.wsztajerowski.services.options.JmhIterationOptions.jmhIterationOptionsBuilder;
import static pl.wsztajerowski.services.options.JmhJvmOptions.jmhJvmOptionsBuilder;
import static pl.wsztajerowski.services.options.JmhOutputOptions.jmhOutputOptionsBuilder;
import static pl.wsztajerowski.services.options.JmhWarmupOptions.jmhWarmupOptionsBuilder;

class JmhSubcommandServiceIT extends TestcontainersWithS3AndMongoBaseIT {

    private static MongoDbTestHelpers helper;

    @BeforeAll
    static void setupHelper(){
        helper = new MongoDbTestHelpers(getConnectionString());
    }

    @Test
    void successful_scenario() throws IOException {
        // given
        Path result = Files.createTempFile("results", "jmh.json");
        Path output = Files.createTempFile("outputs", "jmh.txt");
        Path jmhTestBenchmark = Path.of("target", "fake-jmh-benchmarks.jar").toAbsolutePath();
        JmhSubcommandService sut = serviceBuilder()
            .withResultsStore(new MongoResultsStore(datastore()))
            .withCommonOptions(new CommonSharedOptions(Path.of("test-1"), "req-1", "test-project", Collections.emptyMap()))
            .withJmhOptions( new JmhOptions(
                jmhBenchmarkOptionsBuilder()
                    .withBenchmarkPath(jmhTestBenchmark)
                    .withForks(1)
                    .build(),
                jmhOutputOptionsBuilder()
                    .withMachineReadableOutput(result)
                    .withProcessOutput(output)
                    .build(),
                jmhWarmupOptionsBuilder()
                    .withWarmupIterations(0)
                    .build(),
                jmhIterationOptionsBuilder()
                    .withIterations(1)
                    .build(),
                jmhJvmOptionsBuilder().build()))
            .withStorageService(new S3StorageService(awsS3Client, TEST_BUCKET_NAME))
            .build();

        // when
        sut.executeCommand();

        // then
        String collectionName = MongoMeasurementDocument.class.getAnnotation(Entity.class).value();
        helper.assertFindResult(collectionName, all(), documents ->
            assertThat(documents.first())
                .isNotNull()
                .extracting("measurement", as(MAP))
                .containsEntry("project", "test-project")
                .extracting(measurement -> measurement.get("benchmarkMethod"), as(STRING))
                .isEqualTo("incrementUsingSynchronized")
        );

        // and
        JSONArray objectsInTestBucket = listObjectsInTestBucket();
        assertThatJson(objectsInTestBucket)
            .inPath("$[*].Key")
            .isArray()
            .anySatisfy(o -> assertThat(o)
                .asString()
                .isEqualTo("test-1/jmh-output.txt"));

        // and the thin stored item stays defensible: full fidelity is in the verbatim result JSON
        assertThatJson(objectsInTestBucket)
            .inPath("$[*].Key")
            .isArray()
            .anySatisfy(o -> assertThat(o)
                .asString()
                .isEqualTo("test-1/jmh-result.json"));
    }


}
