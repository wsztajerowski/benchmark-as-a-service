package pl.wsztajerowski.baas.model;

import org.junit.jupiter.api.Test;

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
}
