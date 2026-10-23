package pl.wsztajerowski.services;

import org.json.JSONArray;
import org.junit.jupiter.api.Test;
import pl.wsztajerowski.TestcontainersWithDynamoDbBaseIT;
import pl.wsztajerowski.baas.model.StoredMeasurement;
import pl.wsztajerowski.infra.S3StorageService;
import pl.wsztajerowski.options.CommonSharedOptions;
import pl.wsztajerowski.options.JmhOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;
import static pl.wsztajerowski.options.JmhBenchmarkOptions.jmhBenchmarkOptionsBuilder;
import static pl.wsztajerowski.options.JmhIterationOptions.jmhIterationOptionsBuilder;
import static pl.wsztajerowski.options.JmhJvmOptions.jmhJvmOptionsBuilder;
import static pl.wsztajerowski.options.JmhOutputOptions.jmhOutputOptionsBuilder;
import static pl.wsztajerowski.options.JmhWarmupOptions.jmhWarmupOptionsBuilder;

class JmhWithProfilerSubcommandServiceIT extends TestcontainersWithDynamoDbBaseIT {


    @Test
    void successful_scenario() throws IOException {
        // given
        Path jmhTestBenchmark = Path.of("..", "fake-jmh-benchmarks", "target", "fake-jmh-benchmarks.jar").toAbsolutePath();
        Path result = Files.createTempFile("results", "jmh.json");
        Path output = Files.createTempFile("outputs", "jmh.txt");
        Path profileResults = Files.createTempDirectory("prof-results");
        JmhWithProfilerSubcommandService sut = new JmhWithProfilerSubcommandService(
            new S3StorageService(awsS3Client, TEST_BUCKET_NAME),
            resultsStore(),
            new CommonSharedOptions(profileResults, "req-1", Instant.now(), "test-project", Map.of()),
            new JmhOptions(
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
                jmhJvmOptionsBuilder().build()),
            Map.of(
                "gc", "churn=false;alloc=false",
                "comp", "",
                "cl", "",
                "jfr", "stackDepth=20"));

        // when
        sut.executeCommand();

        // then
        assertThat(storedMeasurements()).singleElement()
            .extracting(StoredMeasurement::secondaryMetrics, as(MAP))
            .containsKeys("gc.count", "class.load", "compiler.time.total")
            .doesNotContainKeys("gc.churn.G1_Eden_Space", "gc.alloc.rate");

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

    /**
     * Only {@code async} and {@code jfr} write artifacts into a per-benchmark directory; the profilers
     * reporting secondary metrics alone ({@code gc}, {@code comp}, {@code cl}, ...) create none. The
     * test above always includes {@code jfr}, which is how a job with {@code gc} alone could fail
     * after the benchmark finished, before its measurements were stored (finding A12).
     */
    @Test
    void a_profiler_that_writes_no_artifacts_still_stores_its_measurements() throws IOException {
        // given
        Path jmhTestBenchmark = Path.of("..", "fake-jmh-benchmarks", "target", "fake-jmh-benchmarks.jar").toAbsolutePath();
        Path result = Files.createTempFile("results", "jmh.json");
        Path output = Files.createTempFile("outputs", "jmh.txt");
        Path profileResults = Files.createTempDirectory("prof-results");
        JmhWithProfilerSubcommandService sut = new JmhWithProfilerSubcommandService(
            new S3StorageService(awsS3Client, TEST_BUCKET_NAME),
            resultsStore(),
            new CommonSharedOptions(profileResults, "req-gc-only", Instant.now(), "test-project", Map.of()),
            new JmhOptions(
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
                jmhJvmOptionsBuilder().build()),
            Map.of("gc", ""));

        // when
        sut.executeCommand();

        // then
        assertThat(storedMeasurements())
            .filteredOn(measurement -> measurement.jobId().equals("req-gc-only"))
            .singleElement()
            .extracting(StoredMeasurement::secondaryMetrics, as(MAP))
            .containsKeys("gc.count");
    }
}