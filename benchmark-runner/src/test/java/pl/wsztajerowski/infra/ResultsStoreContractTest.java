package pl.wsztajerowski.infra;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.model.StoredMeasurement;

import java.util.List;

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

    @Test
    default void anEmptyWriteStoresNothingAndDoesNotThrow() {
        store().write(List.of());

        assertThat(storedCount()).isZero();
    }
}
