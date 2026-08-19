package pl.wsztajerowski.infra;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResultsStoreBuilderTest {

    private static final String REGION_PROPERTY = "aws.region";

    /**
     * Building the DynamoDB adapter constructs a real client, and the SDK resolves its region from
     * the ambient environment — IMDS on the runner, {@code AWS_REGION} in CI. A developer machine
     * has neither, so the region is supplied here rather than defaulted in the builder, where it
     * would mask a genuine misconfiguration in production.
     */
    @AfterEach
    void clearRegion() {
        System.clearProperty(REGION_PROPERTY);
    }

    @Test
    void aTableNameSelectsDynamoDb() {
        System.setProperty(REGION_PROPERTY, "eu-central-1");

        assertThat(ResultsStoreBuilder.builder().withTableName("results").build())
            .isInstanceOf(DynamoDbResultsStore.class);
    }

    @Test
    void noDatabaseSelectsTheExplicitDiscardStore() {
        assertThat(ResultsStoreBuilder.builder().withNoDatabase(true).build())
            .isInstanceOf(NoOpResultsStore.class);
    }

    @Test
    void absentConfigurationIsAHardFailureRatherThanASilentDiscard() {
        assertThatThrownBy(() -> ResultsStoreBuilder.builder().build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("--no-database");
    }

    @Test
    void anEmptyConnectionStringIsAlsoAHardFailure() {
        assertThatThrownBy(() -> ResultsStoreBuilder.builder()
            .withConnectionString(URI.create("")).build())
            .as("the previous behaviour silently discarded measurements here")
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void namingBothStoresIsRejectedRatherThanPickingOne() {
        assertThatThrownBy(() -> ResultsStoreBuilder.builder()
            .withTableName("results")
            .withConnectionString(URI.create("mongodb://localhost:27017/baas"))
            .build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("both");
    }

    @Test
    void noDatabaseAlongsideAStoreIsRejected() {
        assertThatThrownBy(() -> ResultsStoreBuilder.builder()
            .withTableName("results")
            .withNoDatabase(true)
            .build())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aConnectionStringWithoutADatabaseNameIsRejected() {
        assertThatThrownBy(() -> ResultsStoreBuilder.builder()
            .withConnectionString(URI.create("mongodb://localhost:27017"))
            .build())
            .hasMessageContaining("database name");
    }
}
