package pl.wsztajerowski.services;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.TestcontainersWithDynamoDbBaseIT;
import pl.wsztajerowski.infra.DynamoDbResultsStore;
import pl.wsztajerowski.infra.ResultsStore;
import pl.wsztajerowski.infra.ResultsStoreException;
import pl.wsztajerowski.infra.S3StorageService;
import pl.wsztajerowski.services.options.CommonSharedOptions;
import pl.wsztajerowski.services.options.JmhOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pl.wsztajerowski.baas.model.MeasurementItemMapper.PK;
import static pl.wsztajerowski.baas.model.MeasurementItemMapper.fromItem;
import static pl.wsztajerowski.baas.model.ResultKeys.PK_PREFIX;
import static pl.wsztajerowski.services.JmhSubcommandServiceBuilder.serviceBuilder;
import static pl.wsztajerowski.services.options.JmhBenchmarkOptions.jmhBenchmarkOptionsBuilder;
import static pl.wsztajerowski.services.options.JmhIterationOptions.jmhIterationOptionsBuilder;
import static pl.wsztajerowski.services.options.JmhJvmOptions.jmhJvmOptionsBuilder;
import static pl.wsztajerowski.services.options.JmhOutputOptions.jmhOutputOptionsBuilder;
import static pl.wsztajerowski.services.options.JmhWarmupOptions.jmhWarmupOptionsBuilder;

/**
 * The run-level guarantees the DynamoDB write path exists to provide: one item per measurement and
 * nothing else, S3 artifacts that survive a store failure, and a stored timestamp that is the one
 * the caller supplied rather than one read here.
 */
class JmhStoreIntegrationIT extends TestcontainersWithDynamoDbBaseIT {

    /** Truncated to milliseconds, because that is the precision StoredMeasurement stores. */
    private static final Instant LAUNCH_INSTANT = Instant.parse("2026-08-20T17:44:32.812Z");

    @Test
    void aStoredRunProducesOneItemPerMeasurementAndNoOthers() throws IOException {
        runJmhServiceAgainstTheFakeBenchmark(new DynamoDbResultsStore(dynamoDbClient, TEST_TABLE_NAME));

        var items = scanTestTable();
        assertThat(items)
            .as("the fake benchmark declares exactly one benchmark method")
            .hasSize(1);
        assertThat(items)
            .as("exactly one item per measurement — the design has no derived index items")
            .allSatisfy(item -> assertThat(item.get(PK).s()).startsWith(PK_PREFIX));
    }

    /**
     * The whole one-instant-per-run property rests on {@code JmhRunResults} using
     * {@code commonOptions.createdAt()} rather than reading its own clock — a single line that
     * would compile and pass every other test if it were reverted. The launching CLI mints one
     * instant, embeds it in the run id and passes it as {@code --created-at}, so a run identifier
     * and its measurements' timestamps cannot disagree; reading the clock here would break that
     * silently, and only for runs whose instance clock had drifted.
     *
     * <p>The instant is deliberately in the past, so a reverted implementation fails loudly rather
     * than landing within a tolerance of {@code Instant.now()}.
     */
    @Test
    void theStoredTimestampIsTheCallersInstantNotOneReadHere() throws IOException {
        runJmhServiceAgainstTheFakeBenchmark(
            new DynamoDbResultsStore(dynamoDbClient, TEST_TABLE_NAME), LAUNCH_INSTANT);

        assertThat(scanTestTable())
            .singleElement()
            .satisfies(item -> assertThat(fromItem(item).createdAt()).isEqualTo(LAUNCH_INSTANT));
    }

    @Test
    void aStoreFailureExitsNonZeroAndLeavesS3ArtifactsIntact() {
        var storeAtAMissingTable = new DynamoDbResultsStore(dynamoDbClient, "table-that-does-not-exist");

        assertThatThrownBy(() -> runJmhServiceAgainstTheFakeBenchmark(storeAtAMissingTable))
            .isInstanceOf(ResultsStoreException.class);

        assertThat(listObjectsInTestBucket().toString())
            .as("a failed run must still be diagnosable from its S3 artifacts")
            .contains("jmh-result.json");
    }

    private void runJmhServiceAgainstTheFakeBenchmark(ResultsStore resultsStore) throws IOException {
        runJmhServiceAgainstTheFakeBenchmark(resultsStore, Instant.now());
    }

    private void runJmhServiceAgainstTheFakeBenchmark(ResultsStore resultsStore, Instant createdAt)
        throws IOException {
        Path result = Files.createTempFile("results", "jmh.json");
        Path output = Files.createTempFile("outputs", "jmh.txt");
        Path jmhTestBenchmark = Path.of("target", "fake-jmh-benchmarks.jar").toAbsolutePath();

        serviceBuilder()
            .withResultsStore(resultsStore)
            .withCommonOptions(new CommonSharedOptions(Path.of("test-1"), "req-1", createdAt, "test-project", Collections.emptyMap()))
            .withJmhOptions(new JmhOptions(
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
            .build()
            .executeCommand();
    }
}
