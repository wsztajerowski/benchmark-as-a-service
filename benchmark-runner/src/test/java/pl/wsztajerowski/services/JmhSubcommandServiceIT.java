package pl.wsztajerowski.services;

import org.json.JSONArray;
import org.junit.jupiter.api.Test;
import pl.wsztajerowski.JavaWonderlandException;
import pl.wsztajerowski.TestcontainersWithDynamoDbBaseIT;
import pl.wsztajerowski.infra.S3StorageService;
import pl.wsztajerowski.options.CommonSharedOptions;
import pl.wsztajerowski.options.JmhRun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.time.Instant;
import java.util.Collections;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JmhSubcommandServiceIT extends TestcontainersWithDynamoDbBaseIT {


    @Test
    void successful_scenario() throws IOException {
        // given
        Path jmhTestBenchmark = Path.of("target", "fake-jmh-benchmarks.jar").toAbsolutePath();
        JmhSubcommandService sut = new JmhSubcommandService(
            new S3StorageService(awsS3Client, TEST_BUCKET_NAME),
            resultsStore(),
            new CommonSharedOptions(Path.of("test-1"), "req-1", Instant.now(), "test-project", Collections.emptyMap()),
            new JmhRun(jmhTestBenchmark, List.of("Incrementing_Synchronized", "-f", "1", "-wi", "0", "-i", "1"),
                Files.createTempDirectory("jmh"), Path.of("no-async-profiler")));

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

    /** A listing job's answer is its process output; it measured nothing, so it stores nothing. */
    @Test
    void aProfilerListingUploadsItsOutputAndStoresNoMeasurement() throws IOException {
        // given
        Path jmhTestBenchmark = Path.of("target", "fake-jmh-benchmarks.jar").toAbsolutePath();
        JmhSubcommandService sut = new JmhSubcommandService(
            new S3StorageService(awsS3Client, TEST_BUCKET_NAME),
            resultsStore(),
            new CommonSharedOptions(Path.of("test-listing"), "req-listing", Instant.now(), "test-project", Collections.emptyMap()),
            new JmhRun(jmhTestBenchmark, List.of("-lprof"), Files.createTempDirectory("jmh"), Path.of("no-async-profiler")));

        // when
        sut.executeCommand();

        // then
        assertThat(storedMeasurements()).isEmpty();
        JSONArray objectsInTestBucket = listObjectsInTestBucket();
        assertThatJson(objectsInTestBucket)
            .inPath("$[*].Key")
            .isArray()
            .anySatisfy(o -> assertThat(o).asString().isEqualTo("test-listing/jmh-output.txt"))
            .noneSatisfy(o -> assertThat(o).asString().isEqualTo("test-listing/jmh-result.json"));
    }

    /**
     * PT2: {@code size=notanint} fails one of the two variants. Under BaaS's default {@code -foe true}
     * the job fails and stores nothing; its process output is still uploaded.
     */
    @Test
    void aFailedBenchmarkFailsTheJobByDefault() throws IOException {
        JmhSubcommandService sut = service("test-foe", List.of("Summing_Array", "-p", "size=1,notanint"));

        assertThatThrownBy(sut::executeCommand).isInstanceOf(JavaWonderlandException.class);

        assertThat(storedMeasurements()).isEmpty();
        assertThatJson(listObjectsInTestBucket())
            .inPath("$[*].Key")
            .isArray()
            .anySatisfy(o -> assertThat(o).asString().isEqualTo("test-foe/jmh-output.txt"));
    }

    /** {@code -foe false} is the user's way back to JMH's semantics: the variant that succeeded is stored. */
    @Test
    void foeFalseStoresTheBenchmarksThatSucceeded() throws IOException {
        service("test-no-foe", List.of("Summing_Array", "-p", "size=1,notanint", "-foe", "false")).executeCommand();

        assertThat(storedMeasurements()).singleElement()
            .satisfies(measurement -> assertThat(measurement.params()).containsEntry("size", "1"));
    }

    /** PT2's observed case: every benchmark failed, JMH exited 0 with {@code [ ]}, and the job said completed. */
    @Test
    void aRunThatStoresNoMeasurementFails() throws IOException {
        JmhSubcommandService sut = service("test-empty",
            List.of("Incrementing_Synchronized", "-jvmArgsAppend", "-XX:+DefinitelyNotAFlag", "-foe", "false"));

        assertThatThrownBy(sut::executeCommand)
            .isInstanceOf(JavaWonderlandException.class)
            .hasMessageContaining("wrote no measurement");

        assertThat(storedMeasurements()).isEmpty();
    }

    private JmhSubcommandService service(String resultPath, List<String> arguments) throws IOException {
        List<String> quick = new java.util.ArrayList<>(arguments);
        quick.addAll(List.of("-f", "1", "-wi", "0", "-i", "1", "-r", "100ms"));
        return new JmhSubcommandService(
            new S3StorageService(awsS3Client, TEST_BUCKET_NAME),
            resultsStore(),
            new CommonSharedOptions(Path.of(resultPath), "req-" + resultPath, Instant.now(), "test-project", Collections.emptyMap()),
            new JmhRun(Path.of("target", "fake-jmh-benchmarks.jar").toAbsolutePath(), quick,
                Files.createTempDirectory("jmh"), Path.of("no-async-profiler")));
    }
}
