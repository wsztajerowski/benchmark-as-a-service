package pl.wsztajerowski.infra;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResultsStoreBuilderTest {

    @Test
    void aTableNameSelectsDynamoDb() {
        assertThat(ResultsStoreBuilder.builder()
            .withTableName("results")
            .withDynamoDbClient(new RecordingDynamoDbClient())
            .build())
            .isInstanceOf(DynamoDbResultsStore.class);
    }

    @Test
    void absentConfigurationIsAHardFailureRatherThanASilentDiscard() {
        assertThatThrownBy(() -> ResultsStoreBuilder.builder().build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("--results-table")
            .as("no option discards measurements any more")
            .hasMessageNotContaining("--no-database");
    }

    @Test
    void aBlankTableNameIsAlsoAHardFailure() {
        assertThatThrownBy(() -> ResultsStoreBuilder.builder().withTableName(" ").build())
            .as("a blank name used to fall through to another store or none")
            .isInstanceOf(IllegalStateException.class);
    }
}
