package pl.wsztajerowski.services;

import org.json.JSONArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import pl.wsztajerowski.TestcontainersWithDynamoDbBaseIT;
import pl.wsztajerowski.baas.model.StoredMeasurement;
import pl.wsztajerowski.infra.S3StorageService;
import pl.wsztajerowski.options.AsyncProfilerOptions;
import pl.wsztajerowski.options.CommonSharedOptions;
import pl.wsztajerowski.options.JmhOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static pl.wsztajerowski.options.JmhBenchmarkOptions.jmhBenchmarkOptionsBuilder;
import static pl.wsztajerowski.options.JmhIterationOptions.jmhIterationOptionsBuilder;
import static pl.wsztajerowski.options.JmhJvmOptions.jmhJvmOptionsBuilder;
import static pl.wsztajerowski.options.JmhOutputOptions.jmhOutputOptionsBuilder;
import static pl.wsztajerowski.options.JmhWarmupOptions.jmhWarmupOptionsBuilder;

@EnabledIfEnvironmentVariable(named = "ASYNC_PATH", matches = ".*")
class JmhWithAsyncProfilerSubcommandServiceIT  extends TestcontainersWithDynamoDbBaseIT {


    @Test
    void successful_scenario() throws IOException {
        // given
        Path jmhTestBenchmark = Path.of("target", "fake-jmh-benchmarks.jar").toAbsolutePath();
        Path result = Files.createTempFile("results", "jmh.json");
        Path output = Files.createTempFile("outputs", "jmh.txt");
        Path asyncOutput = Files.createTempDirectory("async-outputs");
        JmhWithAsyncProfilerSubcommandService sut = new JmhWithAsyncProfilerSubcommandService(
            new S3StorageService(awsS3Client, TEST_BUCKET_NAME),
            resultsStore(),
            new CommonSharedOptions(Path.of("test-1"), "req-1", Instant.now(), "test-project", Collections.emptyMap()),
            new JmhOptions(
                jmhBenchmarkOptionsBuilder()
                    .withBenchmarkPath(jmhTestBenchmark)
                    .withForks(1)
                    .withTestNameRegex("Incrementing_Synchronized")
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
                jmhJvmOptionsBuilder().build()),
            AsyncProfilerOptions.asyncProfilerOptionsBuilder()
                .withAsyncPath(Path.of(System.getenv("ASYNC_PATH")))
                .withAsyncOutputType("flamegraph")
                .withAsyncOutputPath(asyncOutput)
                .withAsyncInterval(9990)
                .withAsyncEvent("cpu")
                .build());

        // when
        sut.executeCommand();

        // then
        assertThat(storedMeasurements()).singleElement()
            .extracting(StoredMeasurement::benchmarkMethod)
            .isEqualTo("incrementUsingSynchronized");

        // and: a benchmark without @Param keeps the key it had before the output tree was walked
        String benchmarkDir = "test-1/pl.wsztajerowski.fake.Incrementing_Synchronized.incrementUsingSynchronized-Throughput";
        JSONArray objectsInTestBucket = listObjectsInTestBucket();
        assertThatJson(objectsInTestBucket)
            .inPath("$[*].Key")
            .isArray()
            .anySatisfy(o -> assertThat(o)
                .asString()
                .isEqualTo(benchmarkDir + "/flame-cpu-forward.html"))
            .anySatisfy(o -> assertThat(o)
                .asString()
                .isEqualTo(benchmarkDir + "/flame-cpu-reverse.html"))
            .anySatisfy(o -> assertThat(o)
                .asString()
                .isEqualTo("test-1/jmh-with-async-output.txt"));
    }
}