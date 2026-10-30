package pl.wsztajerowski.services;

import org.json.JSONArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import pl.wsztajerowski.TestcontainersWithDynamoDbBaseIT;
import pl.wsztajerowski.baas.model.StoredMeasurement;
import pl.wsztajerowski.infra.S3StorageService;
import pl.wsztajerowski.options.CommonSharedOptions;
import pl.wsztajerowski.options.JmhRun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ASYNC_PATH", matches = ".*")
class JmhAsyncProfilerIT extends TestcontainersWithDynamoDbBaseIT {


    @Test
    void successful_scenario() throws IOException {
        // given
        Path jmhTestBenchmark = Path.of("target", "fake-jmh-benchmarks.jar").toAbsolutePath();
        JmhSubcommandService sut = new JmhSubcommandService(
            new S3StorageService(awsS3Client, TEST_BUCKET_NAME),
            resultsStore(),
            new CommonSharedOptions(Path.of("test-1"), "req-1", Instant.now(), "test-project", Collections.emptyMap()),
            new JmhRun(jmhTestBenchmark,
                List.of("Incrementing_Synchronized", "-f", "1", "-wi", "0", "-i", "1",
                    "-prof", "async:output=flamegraph;event=cpu;interval=9990"),
                Files.createTempDirectory("jmh"), Path.of(System.getenv("ASYNC_PATH"))));

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
                .isEqualTo("test-1/jmh-output.txt"));
    }
}