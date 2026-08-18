package pl.wsztajerowski.baas.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoredMeasurementTest {

    @Test
    void aJmhMeasurementCarriesItsBenchmarkCoordinates() {
        var m = StoredMeasurementFixtures.jmh();

        assertThat(m.kind()).isEqualTo(MeasurementKind.JMH);
        assertThat(m.benchmarkClass()).isEqualTo("pl.wsztajerowski.fake.Incrementing_Synchronized");
        assertThat(m.benchmarkMethod()).isEqualTo("incrementUsingSynchronized");
        assertThat(m.jcstress()).isNull();
    }

    @Test
    void aJcstressMeasurementHasNoBenchmarkMethodButCarriesCounts() {
        var m = StoredMeasurementFixtures.jcstress();

        assertThat(m.kind()).isEqualTo(MeasurementKind.JCSTRESS);
        assertThat(m.benchmarkMethod()).isNull();
        assertThat(m.jcstress().totalTests()).isEqualTo(12);
    }

    @Test
    void tagsAreDefensivelyCopiedSoAStoredMeasurementCannotBeMutatedAfterConstruction() {
        var mutable = new java.util.HashMap<String, String>();
        mutable.put("project", "lynx-journal");
        var m = StoredMeasurementFixtures.jmh().withTags(mutable);

        mutable.put("project", "tampered");

        assertThat(m.tags()).containsEntry("project", "lynx-journal");
    }

    @Test
    void aJmhMeasurementRequiresItsBenchmarkCoordinates() {
        assertThatThrownBy(() -> StoredMeasurementFixtures.jmh().withBenchmarkClass(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("benchmarkClass");
    }

    /**
     * The sort-key format (ResultKeys.formatTimestamp) carries exactly three fractional digits.
     * createdAt must be truncated to that precision at construction, or fromItem(toItem(m)) would
     * come back unequal to m for any clock with finer resolution — which OffsetDateTime.now() has
     * on modern JVMs.
     */
    @Test
    void createdAtIsTruncatedToMillisecondPrecisionAtConstruction() {
        var m = new StoredMeasurement(
            "lynx-journal",
            "jmh-20260817_220706",
            Instant.parse("2026-08-17T22:07:06.123456789Z"),
            MeasurementKind.JMH,
            "pl.wsztajerowski.fake.Incrementing_Synchronized",
            "incrementUsingSynchronized",
            "thrpt",
            14075511.867,
            10632927.824,
            "ops/s",
            Map.of(),
            null,
            Map.of(),
            "main/jmh/20260817_220706",
            "main/jmh/20260817_220706/jmh-result.json",
            "main/jmh/20260817_220706/environment.json");

        assertThat(m.createdAt()).isEqualTo(Instant.parse("2026-08-17T22:07:06.123Z"));
    }
}
