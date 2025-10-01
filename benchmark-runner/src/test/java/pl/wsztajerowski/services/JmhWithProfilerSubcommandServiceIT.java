package pl.wsztajerowski.services;

import dev.morphia.annotations.Entity;
import org.json.JSONArray;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pl.wsztajerowski.MongoDbTestHelpers;
import pl.wsztajerowski.TestcontainersWithS3AndMongoBaseIT;
import pl.wsztajerowski.entities.jmh.JmhBenchmark;
import pl.wsztajerowski.infra.S3StorageService;
import pl.wsztajerowski.services.options.CommonSharedOptions;
import pl.wsztajerowski.services.options.JmhOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;
import static pl.wsztajerowski.MongoDbTestHelpers.all;
import static pl.wsztajerowski.services.options.JmhBenchmarkOptions.jmhBenchmarkOptionsBuilder;
import static pl.wsztajerowski.services.options.JmhIterationOptions.jmhIterationOptionsBuilder;
import static pl.wsztajerowski.services.options.JmhJvmOptions.jmhJvmOptionsBuilder;
import static pl.wsztajerowski.services.options.JmhOutputOptions.jmhOutputOptionsBuilder;
import static pl.wsztajerowski.services.options.JmhWarmupOptions.jmhWarmupOptionsBuilder;

class JmhWithProfilerSubcommandServiceIT extends TestcontainersWithS3AndMongoBaseIT {

    private static MongoDbTestHelpers helper;

    @BeforeAll
    static void setupHelper(){
        helper = new MongoDbTestHelpers(getConnectionString());
    }

    @Test
    void successful_scenario() throws IOException {
        // given
        Path jmhTestBenchmark = Path.of("..", "fake-jmh-benchmarks", "target", "fake-jmh-benchmarks.jar").toAbsolutePath();
        Path result = Files.createTempFile("results", "jmh.json");
        Path output = Files.createTempFile("outputs", "jmh.txt");
        Path profileResults = Files.createTempDirectory("prof-results");
        JmhWithProfilerSubcommandService sut = JmhWithProfilerSubcommandServiceBuilder.serviceBuilder()
            .withMongoConnectionString(getConnectionString())
            .withStorageService(new S3StorageService(awsS3Client, TEST_BUCKET_NAME))
            .withCommonOptions(new CommonSharedOptions(profileResults, "req-1", Map.of()))
            .withProfilerOptions(Map.of(
                "gc", "churn=false;alloc=false",
                "comp", "",
                "cl", "",
                "jfr", "stackDepth=20")
            )
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
            .build();

        // when
        sut.executeCommand();

        // then
        String collectionName = JmhBenchmark.class.getAnnotation(Entity.class).value();
        helper.assertFindResult(collectionName, all(), documents ->
            assertThat(documents.first())
                .isNotNull()
                .containsEntry("_t", "JmhBenchmark")
                .extracting("jmhResult.secondaryMetrics", as(MAP))
                .containsKeys("gc.count", "class.load", "compiler.time.total")
                .doesNotContainKeys("gc.churn.G1_Eden_Space", "gc.alloc.rate")
        );

        // and
        JSONArray objectsInTestBucket = listObjectsInTestBucket();
        assertThatJson(objectsInTestBucket)
            .inPath("$[*].Key")
            .isArray()
            .anySatisfy(o -> assertThat(o)
                .asString()
                .endsWith("/profile.jfr"))
            .anySatisfy(o -> assertThat(o)
                .asString()
                .endsWith("/jmh-profiler-output.txt"));
    }
}