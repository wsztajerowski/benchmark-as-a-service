package pl.wsztajerowski.services;

import org.json.JSONArray;
import org.junit.jupiter.api.Test;
import pl.wsztajerowski.TestcontainersWithDynamoDbBaseIT;
import pl.wsztajerowski.infra.S3StorageService;
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

class JmhSubcommandServiceIT extends TestcontainersWithDynamoDbBaseIT {


    @Test
    void successful_scenario() throws IOException {
        // given
        Path result = Files.createTempFile("results", "jmh.json");
        Path output = Files.createTempFile("outputs", "jmh.txt");
        Path jmhTestBenchmark = Path.of("target", "fake-jmh-benchmarks.jar").toAbsolutePath();
        JmhSubcommandService sut = new JmhSubcommandService(
            new S3StorageService(awsS3Client, TEST_BUCKET_NAME),
            resultsStore(),
            new CommonSharedOptions(Path.of("test-1"), "req-1", Instant.now(), "test-project", Collections.emptyMap()),
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
                jmhJvmOptionsBuilder().build()));

        // when
        sut.executeCommand();

        // then
        assertThat(storedMeasurements()).singleElement().satisfies(measurement -> {
            assertThat(measurement.project()).isEqualTo("test-project");
            assertThat(measurement.benchmarkMethod()).isEqualTo("incrementUsingSynchronized");
        });

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
