package pl.wsztajerowski.infra;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamoDbResultsStoreTest {

    @Test
    void splitsMoreThanTwentyFiveMeasurementsAcrossBatches() {
        var client = new RecordingDynamoDbClient();

        new DynamoDbResultsStore(client, "results").write(
            IntStream.range(0, 30)
                .mapToObj(i -> StoredMeasurementFixtures.jmh("method" + i))
                .toList());

        assertThat(client.batchSizes())
            .as("BatchWriteItem caps at 25 items")
            .containsExactly(25, 5);
    }

    @Test
    void retriesUnprocessedItemsRatherThanLosingThem() {
        var client = new RecordingDynamoDbClient();
        client.returnUnprocessedOnFirstCall(2);

        new DynamoDbResultsStore(client, "results").write(List.of(
            StoredMeasurementFixtures.jmh("one"),
            StoredMeasurementFixtures.jmh("two")));

        assertThat(client.callCount())
            .as("unprocessed items must be resubmitted, not dropped")
            .isEqualTo(2);
    }

    @Test
    void throwsWhenItemsRemainUnprocessedAfterEveryRetry() {
        var client = new RecordingDynamoDbClient();
        client.alwaysReturnUnprocessed(1);

        assertThatThrownBy(() ->
            new DynamoDbResultsStore(client, "results").write(List.of(StoredMeasurementFixtures.jmh("one"))))
            .isInstanceOf(ResultsStoreException.class)
            .hasMessageContaining("unprocessed");
    }

    @Test
    void anEmptyListWritesNothingAndDoesNotThrow() {
        var client = new RecordingDynamoDbClient();

        new DynamoDbResultsStore(client, "results").write(List.of());

        assertThat(client.callCount()).isZero();
    }
}
