package pl.wsztajerowski.results;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.model.MeasurementKind;
import pl.wsztajerowski.baas.model.StoredMeasurement;
import pl.wsztajerowski.entities.jmh.JmhResult;
import pl.wsztajerowski.entities.jmh.Metric;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JmhMeasurementMapperTest {

    @Test
    void splitsTheFullyQualifiedBenchmarkIntoClassAndMethod() {
        var measurement = map("pl.wsztajerowski.fake.Incrementing_Synchronized.incrementUsingSynchronized");

        assertThat(measurement.benchmarkClass()).isEqualTo("pl.wsztajerowski.fake.Incrementing_Synchronized");
        assertThat(measurement.benchmarkMethod()).isEqualTo("incrementUsingSynchronized");
    }

    @Test
    void splitsOnTheLastDotSoNestedClassesSurvive() {
        var measurement = map("com.example.Outer$Inner.someMethod");

        assertThat(measurement.benchmarkClass()).isEqualTo("com.example.Outer$Inner");
        assertThat(measurement.benchmarkMethod()).isEqualTo("someMethod");
    }

    @Test
    void aBenchmarkNameWithNoDotIsRejectedRatherThanSilentlyHalved() {
        assertThatThrownBy(() -> map("nodothere"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nodothere");
    }

    @Test
    void carriesTheKindAndTheS3Pointers() {
        var measurement = map("com.example.Bench.method");

        assertThat(measurement.kind()).isEqualTo(MeasurementKind.JMH);
        assertThat(measurement.resultJsonKey()).isEqualTo("main/jmh/ts/jmh-result.json");
        assertThat(measurement.environmentJsonKey()).isEqualTo("main/jmh/ts/environment.json");
    }

    @Test
    void reducesSecondaryMetricsToScoreAndUnit() {
        var measurement = map(result("com.example.Bench.method",
            Map.of("gc.alloc.rate", metric(42.0, "MB/sec"))));

        assertThat(measurement.secondaryMetrics()).containsOnlyKeys("gc.alloc.rate");
        assertThat(measurement.secondaryMetrics().get("gc.alloc.rate").score()).isEqualTo(42.0);
        assertThat(measurement.secondaryMetrics().get("gc.alloc.rate").unit()).isEqualTo("MB/sec");
    }

    @Test
    void dropsASecondaryMetricWithNoScoreRatherThanFailingTheWholeMapping() {
        var withNullScore = new Metric(null, null, null, null, "MB/sec", null);

        var measurement = map(result("com.example.Bench.method",
            Map.of("gc.alloc.rate", withNullScore)));

        assertThat(measurement.secondaryMetrics()).isEmpty();
    }

    private static Metric metric(Double score, String unit) {
        return new Metric(score, 0.5, null, null, unit, null);
    }

    private static JmhResult result(String benchmark, Map<String, Metric> secondaryMetrics) {
        return new JmhResult(
            "1.37", benchmark, "thrpt", 1L, 1L, "jvm", List.of(), "25", "vm", "25",
            1L, "1 s", 1L, 1L, "1 s", 1L,
            metric(1234.5, "ops/s"),
            secondaryMetrics);
    }

    private static StoredMeasurement map(String benchmarkName) {
        return map(result(benchmarkName, Map.of()));
    }

    private static StoredMeasurement map(JmhResult result) {
        return JmhMeasurementMapper.toMeasurement(
            result, "p", "r", Instant.parse("2026-08-19T09:00:00.000Z"), Map.of(),
            "main/jmh/ts", "main/jmh/ts/jmh-result.json", "main/jmh/ts/environment.json");
    }
}
