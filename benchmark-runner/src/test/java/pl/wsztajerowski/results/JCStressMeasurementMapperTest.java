package pl.wsztajerowski.results;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.model.MeasurementKind;
import pl.wsztajerowski.baas.model.StoredMeasurement;
import pl.wsztajerowski.entities.jcstress.JCStressResult;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static pl.wsztajerowski.entities.jcstress.JCStressResultBuilder.getJCStressResult;

class JCStressMeasurementMapperTest {

    @Test
    void producesOneMeasurementWithNoBenchmarkCoordinates() {
        var measurement = map();

        assertThat(measurement.kind()).isEqualTo(MeasurementKind.JCSTRESS);
        assertThat(measurement.benchmarkClass()).isNull();
        assertThat(measurement.benchmarkMethod()).isNull();
        assertThat(measurement.mode()).isNull();
        assertThat(measurement.score()).isNull();
    }

    @Test
    void carriesTheCountsAndTheThreeTestMaps() {
        var summary = map().jcstress();

        assertThat(summary.totalTests()).isEqualTo(12);
        assertThat(summary.passedTests()).isEqualTo(10);
        assertThat(summary.failedTests()).isEqualTo(1);
        assertThat(summary.errorTests()).isEqualTo(1);
        assertThat(summary.failed()).containsKey("SomeFailingTest");
        assertThat(summary.errors()).containsKey("SomeErroringTest");
    }

    @Test
    void derivesTheFailedAndErrorCountsFromTheNamedTests() {
        var result = getJCStressResult()
            .withTotalTests(5)
            .withPassedTests(2)
            .withTestsWithFailedResults(Map.of("a", "a.html", "b", "b.html"))
            .withTestsWithErrorResults(Map.of("c", "c.html"))
            .build();

        var summary = map(result).jcstress();

        assertThat(summary.failedTests())
            .as("JCStressResult carries no failedTests count; it is the size of the named set")
            .isEqualTo(2);
        assertThat(summary.errorTests()).isEqualTo(1);
    }

    @Test
    void hasNoResultJsonKeyBecauseJcstressProducesHtml() {
        assertThat(map().resultJsonKey()).isNull();
    }

    private static StoredMeasurement map() {
        return map(getJCStressResult()
            .withTotalTests(12)
            .withPassedTests(10)
            .withTestsWithFailedResults(Map.of("SomeFailingTest", "failing.html"))
            .withTestsWithErrorResults(Map.of("SomeErroringTest", "erroring.html"))
            .withTestsWithInterestingResults(Map.of("SomeInterestingTest", "interesting.html"))
            .build());
    }

    private static StoredMeasurement map(JCStressResult result) {
        return JCStressMeasurementMapper.toMeasurement(
            result, "p", "r", Instant.parse("2026-08-19T09:00:00.000Z"), Map.of(),
            "main/jcstress/ts", "main/jcstress/ts/environment.json");
    }
}
