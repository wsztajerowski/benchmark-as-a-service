package pl.wsztajerowski.services;

import org.json.JSONArray;
import org.junit.jupiter.api.Test;
import pl.wsztajerowski.TestcontainersWithDynamoDbBaseIT;
import pl.wsztajerowski.baas.model.StoredMeasurement;
import pl.wsztajerowski.infra.S3StorageService;
import pl.wsztajerowski.options.CommonSharedOptions;
import pl.wsztajerowski.options.JmhRun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

/**
 * Profiling is a JMH argument: {@code -prof} passed after {@code --}, with the runner pointing
 * {@code jfr} (and {@code async}, see {@link JmhAsyncProfilerIT}) at the directory it uploads.
 */
class JmhProfilerArgumentsIT extends TestcontainersWithDynamoDbBaseIT {

    private static final Path FAKE_BENCHMARKS =
        Path.of("target", "fake-jmh-benchmarks.jar").toAbsolutePath();

    private JmhSubcommandService service(String jobId, String benchmark, String... profilerArgs) throws IOException {
        List<String> args = new ArrayList<>(List.of(benchmark, "-f", "1", "-wi", "0", "-i", "1"));
        args.addAll(List.of(profilerArgs));
        return new JmhSubcommandService(
            new S3StorageService(awsS3Client, TEST_BUCKET_NAME),
            resultsStore(),
            new CommonSharedOptions(Files.createTempDirectory("prof-results"), jobId, Instant.now(), "test-project", Map.of()),
            new JmhRun(FAKE_BENCHMARKS, args, Files.createTempDirectory("jmh"), Path.of("no-async-profiler")));
    }

    @Test
    void severalProfilersTheUserNamesRunAndJfrWritesUnderTheJobsPrefix() throws IOException {
        // when
        service("req-1", "Incrementing_Synchronized",
            "-prof", "gc:churn=false;alloc=false", "-prof", "comp", "-prof", "cl", "-prof", "jfr:stackDepth=20")
            .executeCommand();

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
                .endsWith("/jmh-output.txt"));
    }

    /**
     * Only {@code async} and {@code jfr} write artifacts into a per-benchmark directory; the profilers
     * reporting secondary metrics alone ({@code gc}, {@code comp}, {@code cl}, ...) create none, and a
     * job with {@code gc} alone once failed after the benchmark finished, before its measurements were
     * stored (finding A12).
     */
    @Test
    void aProfilerThatWritesNoArtifactsStillStoresItsMeasurements() throws IOException {
        // when
        service("req-gc-only", "Incrementing_Synchronized", "-prof", "gc").executeCommand();

        // then
        assertThat(storedMeasurements())
            .filteredOn(measurement -> measurement.jobId().equals("req-gc-only"))
            .singleElement()
            .extracting(StoredMeasurement::secondaryMetrics, as(MAP))
            .containsKeys("gc.count");
    }

    /**
     * JMH names a {@code @Param} benchmark's directory after its parameters as well, so a runner
     * predicting {@code <benchmark>-<Mode>} found none of a sweep's profiles and uploaded nothing for
     * it (issue #115). JFR shares the naming with async-profiler and needs no {@code ASYNC_PATH}.
     * Written abbreviated, as JMH accepts it: the runner points it at its directory all the same.
     */
    @Test
    void everyVariantOfAParamSweepHasItsProfileUploaded() throws IOException {
        // when
        service("req-params", "Summing_Array", "-pro", "jfr").executeCommand();

        // then
        String sweep = "/pl.wsztajerowski.fake.Summing_Array.sum-Throughput";
        assertThatJson(listObjectsInTestBucket())
            .inPath("$[*].Key")
            .isArray()
            .anySatisfy(o -> assertThat(o)
                .asString()
                .endsWith(sweep + "-size-1/profile.jfr"))
            .anySatisfy(o -> assertThat(o)
                .asString()
                .endsWith(sweep + "-size-2/profile.jfr"));
    }
}
