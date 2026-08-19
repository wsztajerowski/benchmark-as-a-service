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
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pl.wsztajerowski.baas.model.MeasurementItemMapper.PK;
import static pl.wsztajerowski.baas.model.ResultKeys.PK_PREFIX;
import static pl.wsztajerowski.services.JmhSubcommandServiceBuilder.serviceBuilder;
import static pl.wsztajerowski.services.options.JmhBenchmarkOptions.jmhBenchmarkOptionsBuilder;
import static pl.wsztajerowski.services.options.JmhIterationOptions.jmhIterationOptionsBuilder;
import static pl.wsztajerowski.services.options.JmhJvmOptions.jmhJvmOptionsBuilder;
import static pl.wsztajerowski.services.options.JmhOutputOptions.jmhOutputOptionsBuilder;
import static pl.wsztajerowski.services.options.JmhWarmupOptions.jmhWarmupOptionsBuilder;

/**
 * The two run-level guarantees the DynamoDB write path exists to provide: one item per measurement
 * and nothing else, and S3 artifacts that survive a store failure.
 */
class JmhStoreIntegrationIT extends TestcontainersWithDynamoDbBaseIT {

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
        Path result = Files.createTempFile("results", "jmh.json");
        Path output = Files.createTempFile("outputs", "jmh.txt");
        Path jmhTestBenchmark = Path.of("target", "fake-jmh-benchmarks.jar").toAbsolutePath();

        serviceBuilder()
            .withResultsStore(resultsStore)
            .withCommonOptions(new CommonSharedOptions(Path.of("test-1"), "req-1", "test-project", Collections.emptyMap()))
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
