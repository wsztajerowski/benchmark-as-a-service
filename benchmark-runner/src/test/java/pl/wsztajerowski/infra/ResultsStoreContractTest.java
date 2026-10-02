package pl.wsztajerowski.infra;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.model.StoredMeasurement;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One suite, both adapters. The port promises the same observable behaviour regardless of backing
 * store, and the only way that stays true is to write the test once and run it twice.
 *
 * <p>An interface rather than an abstract class: each implementation already extends its own
 * Testcontainers base, and Java has one superclass to give.
 */
interface ResultsStoreContractTest {

    ResultsStore store();

    long storedCount();

    @Test
    default void writesOneRecordPerMeasurement() {
        store().write(List.of(
            StoredMeasurementFixtures.jmh("one"),
            StoredMeasurementFixtures.jmh("two")));

        assertThat(storedCount()).isEqualTo(2);
    }

    @Test
    default void aRepeatedWriteIsIdempotent() {
        StoredMeasurement measurement = StoredMeasurementFixtures.jmh("one");

        store().write(List.of(measurement));
        store().write(List.of(measurement));

        assertThat(storedCount())
            .as("a re-run of the same measurement must not double-count it")
            .isEqualTo(1);
    }

    /**
     * Review A11: a {@code @Param} sweep yields one result per combination, all sharing class,
     * method, mode and the run's single timestamp. Keyed on those alone they collided — DynamoDB
     * rejects a batch holding two equal keys, and across batches the last variant overwrote the
     * rest. Every variant must be stored, in one write, as the runner issues it.
     */
    @Test
    default void storesEveryVariantOfAParameterSweep() {
        store().write(List.of(
            StoredMeasurementFixtures.jmh("get", Map.of("impl", "hash", "size", "10"), 182431207.4),
            StoredMeasurementFixtures.jmh("get", Map.of("impl", "hash", "size", "1000"), 151207334.1),
            StoredMeasurementFixtures.jmh("get", Map.of("impl", "tree", "size", "10"), 97310482.2)));

        assertThat(storedCount()).isEqualTo(3);
    }

    @Test
    default void anEmptyWriteStoresNothingAndDoesNotThrow() {
        store().write(List.of());

        assertThat(storedCount()).isZero();
    }
}
