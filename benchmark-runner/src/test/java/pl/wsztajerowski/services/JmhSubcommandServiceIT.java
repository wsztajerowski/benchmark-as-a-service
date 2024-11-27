package pl.wsztajerowski.services;

import dev.morphia.annotations.Entity;
import org.json.JSONArray;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pl.wsztajerowski.MongoDbTestHelpers;
import pl.wsztajerowski.TestcontainersWithS3AndMongoBaseIT;
import pl.wsztajerowski.entities.jmh.JmhBenchmark;
import pl.wsztajerowski.services.options.CommonSharedOptions;
import pl.wsztajerowski.services.options.JmhOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static pl.wsztajerowski.MongoDbTestHelpers.all;
import static pl.wsztajerowski.infra.S3ServiceBuilder.getS3ServiceBuilder;
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
            .withMongoConnectionString(getConnectionString())
            .withCommonOptions(new CommonSharedOptions(Path.of("test-1"), "req-1"))
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
            .withS3Service(getS3ServiceBuilder()
                .withS3Client(awsS3Client)
                .withBucketName(TEST_BUCKET_NAME)
                .build())
            .build();

        // when
        sut.executeCommand();

        // then
        String collectionName = JmhBenchmark.class.getAnnotation(Entity.class).value();
        helper.assertFindResult(collectionName, all(), documents ->
            assertThat(documents.first())
                .isNotNull()
                .containsEntry("_t", "JmhBenchmark")
                .extracting("_id.benchmarkName", as(STRING))
                .endsWith("incrementUsingSynchronized")
        );

        // and
        JSONArray objectsInTestBucket = listObjectsInTestBucket();
        assertThatJson(objectsInTestBucket)
            .inPath("$[*].Key")
            .isArray()
            .anySatisfy(o -> assertThat(o)
                .asString()
                .isEqualTo("test-1/jmh/output.txt"));
    }


}