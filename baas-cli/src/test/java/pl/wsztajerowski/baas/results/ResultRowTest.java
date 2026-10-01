package pl.wsztajerowski.baas.results;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.model.MeasurementKind;
import pl.wsztajerowski.baas.model.StoredMeasurement;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResultRowTest {

    private static StoredMeasurement measurement(Double score, Double scoreError) {
        return new StoredMeasurement("lynx-journal", "20260930T112913537Z-f719ff9f",
            Instant.parse("2026-09-30T11:29:13.537Z"), MeasurementKind.JMH,
            "com.example.MyBenchmark", "run", "thrpt", score, scoreError, "ops/s",
            Map.of(), null, Map.of(), null, null, null, null);
    }

    /**
     * The store leaves a non-finite score error out of the item, since DynamoDB's {@code N}
     * rejects NaN, and JMH reports one for any run with fewer than three iterations. Reading
     * that absence back as 0 made every renderer claim a perfectly precise measurement, and the
     * JSON renderer's non-finite-to-null rule never got a NaN to act on. Found on a real
     * {@code -i 2} run.
     */
    @Test
    void anAbsentScoreErrorStaysUnknownRatherThanBecomingZero() {
        ResultRow row = ResultRow.from(measurement(1000.0, null));

        assertThat(row.scoreError()).isNaN();
        assertThat(row.score()).isEqualTo(1000.0);
    }

    @Test
    void anAbsentScoreStaysUnknownRatherThanBecomingZero() {
        assertThat(ResultRow.from(measurement(null, null)).score()).isNaN();
    }

    @Test
    void aStoredScoreErrorIsCarriedThrough() {
        assertThat(ResultRow.from(measurement(1000.0, 12.5)).scoreError()).isEqualTo(12.5);
    }
}
