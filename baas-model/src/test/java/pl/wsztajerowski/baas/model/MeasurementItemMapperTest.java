package pl.wsztajerowski.baas.model;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeasurementItemMapperTest {

    @Test
    void aJmhMeasurementRoundTrips() {
        var original = StoredMeasurementFixtures.jmh();

        assertThat(MeasurementItemMapper.fromItem(MeasurementItemMapper.toItem(original)))
            .isEqualTo(original);
    }

    @Test
    void aJcstressMeasurementRoundTrips() {
        var original = StoredMeasurementFixtures.jcstress();

        assertThat(MeasurementItemMapper.fromItem(MeasurementItemMapper.toItem(original)))
            .isEqualTo(original);
    }

    @Test
    void theItemCarriesBothKeyPairs() {
        var item = MeasurementItemMapper.toItem(StoredMeasurementFixtures.jmh());

        assertThat(item.get("pk").s()).isEqualTo("RESULT#lynx-journal");
        assertThat(item.get("sk").s()).startsWith("pl.wsztajerowski.fake.Incrementing_Synchronized#");
        assertThat(item.get("gsi1pk").s()).isEqualTo("jmh-20260817_220706");
        assertThat(item.get("gsi1sk").s())
            .isEqualTo("pl.wsztajerowski.fake.Incrementing_Synchronized#incrementUsingSynchronized#thrpt");
    }

    @Test
    void absentOptionalAttributesAreOmittedRatherThanStoredAsNull() {
        var item = MeasurementItemMapper.toItem(StoredMeasurementFixtures.jcstress());

        assertThat(item).doesNotContainKey("resultJsonKey");
        assertThat(item).doesNotContainKey("benchmarkMethod");
    }

    @Test
    void aRealisticMeasurementIsFarUnderTheItemLimit() {
        var bytes = MeasurementItemMapper.serializedSize(
            MeasurementItemMapper.toItem(StoredMeasurementFixtures.jmh()));

        assertThat(bytes).isLessThan(4 * 1024);
    }

    @Test
    void anOversizedMeasurementFailsLoudlyRatherThanBeingTruncated() {
        Map<String, String> hugeTags = IntStream.range(0, 20_000)
            .boxed()
            .collect(Collectors.toMap(i -> "key" + i, i -> "value".repeat(10)));

        var oversized = StoredMeasurementFixtures.jmh().withTags(hugeTags);

        assertThatThrownBy(() -> MeasurementItemMapper.toItem(oversized))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("400")
            .hasMessageContaining("jmh-20260817_220706");
    }

    @Test
    void anUnknownAttributeInStoredDataDoesNotBreakReads() {
        var item = new HashMap<>(MeasurementItemMapper.toItem(StoredMeasurementFixtures.jmh()));
        item.put("attributeAddedByALaterVersion", AttributeValue.fromS("whatever"));

        assertThat(MeasurementItemMapper.fromItem(item))
            .isEqualTo(StoredMeasurementFixtures.jmh());
    }

    /**
     * DynamoDB's N type rejects NaN outright, and JMH reports NaN scoreError for any
     * single-iteration run — a real, not hypothetical, case. Normalizing to absent (rather than
     * letting PutItem reject the whole item) matches the convention ResultsCommand already applies
     * when formatting for display. This makes the round trip deliberately LOSSY for non-finite
     * input: NaN goes in, null comes back. That is intentional — do not "fix" it back.
     */
    @Test
    void aNaNScoreErrorIsNormalizedToAbsentRatherThanRejectedByDynamoDb() {
        var original = StoredMeasurementFixtures.jmh();
        var withNaNScoreError = new StoredMeasurement(
            original.project(), original.requestId(), original.createdAt(), original.kind(),
            original.benchmarkClass(), original.benchmarkMethod(), original.mode(),
            original.score(), Double.NaN, original.scoreUnit(),
            original.secondaryMetrics(), original.jcstress(), original.tags(),
            original.resultPath(), original.resultJsonKey(), original.environmentJsonKey(),
            original.profilerOutputPath());

        var item = MeasurementItemMapper.toItem(withNaNScoreError);

        assertThat(item).doesNotContainKey("scoreError");
        assertThat(MeasurementItemMapper.fromItem(item).scoreError()).isNull();
    }

    /**
     * Same normalization as the NaN scoreError case above, but for score and +Infinity instead of
     * NaN — DynamoDB's N type rejects both equally. Deliberately lossy: Infinity in, null out.
     */
    @Test
    void aPositiveInfinityScoreIsNormalizedToAbsentRatherThanRejectedByDynamoDb() {
        var original = StoredMeasurementFixtures.jmh();
        var withInfiniteScore = new StoredMeasurement(
            original.project(), original.requestId(), original.createdAt(), original.kind(),
            original.benchmarkClass(), original.benchmarkMethod(), original.mode(),
            Double.POSITIVE_INFINITY, original.scoreError(), original.scoreUnit(),
            original.secondaryMetrics(), original.jcstress(), original.tags(),
            original.resultPath(), original.resultJsonKey(), original.environmentJsonKey(),
            original.profilerOutputPath());

        var item = MeasurementItemMapper.toItem(withInfiniteScore);

        assertThat(item).doesNotContainKey("score");
        assertThat(MeasurementItemMapper.fromItem(item).score()).isNull();
    }

    /**
     * JMH's GCProfiler computes rates as allocated / durationSeconds, so a zero-duration window
     * yields a non-finite secondary metric — a real case, not a defensive one. score/scoreError
     * already normalize non-finite values to absent (see the two tests above); secondaryMetrics
     * needs the same guard, per entry, or DynamoDB's N type rejects the whole PutItem with an
     * opaque ValidationException and the entire measurement is lost over one bad rate.
     */
    @Test
    void aSecondaryMetricWithANonFiniteScoreIsDroppedRatherThanRejectedByDynamoDb() {
        var original = StoredMeasurementFixtures.jmh();
        var withBadSecondaryMetric = new StoredMeasurement(
            original.project(), original.requestId(), original.createdAt(), original.kind(),
            original.benchmarkClass(), original.benchmarkMethod(), original.mode(),
            original.score(), original.scoreError(), original.scoreUnit(),
            Map.of(
                "gc.alloc.rate", new SecondaryMetric(Double.NaN, "MB/sec"),
                "gc.alloc.rate.norm", new SecondaryMetric(42.0, "B/op")),
            original.jcstress(), original.tags(),
            original.resultPath(), original.resultJsonKey(), original.environmentJsonKey(),
            original.profilerOutputPath());

        var item = MeasurementItemMapper.toItem(withBadSecondaryMetric);

        var secondaryMetrics = item.get("secondaryMetrics").m();
        assertThat(secondaryMetrics).doesNotContainKey("gc.alloc.rate");
        assertThat(secondaryMetrics).containsKey("gc.alloc.rate.norm");
        assertThat(MeasurementItemMapper.fromItem(item).secondaryMetrics())
            .doesNotContainKey("gc.alloc.rate")
            .containsKey("gc.alloc.rate.norm");
    }

    /**
     * s(null) yields an AttributeValue with no datatype set, which DynamoDB rejects with "Supplied
     * AttributeValue is empty" — the same class of whole-PutItem failure as the non-finite case
     * above, just triggered by a null unit instead of a bad score.
     */
    @Test
    void aSecondaryMetricWithANullUnitIsDroppedRatherThanStoredEmpty() {
        var original = StoredMeasurementFixtures.jmh();
        var withNullUnit = new StoredMeasurement(
            original.project(), original.requestId(), original.createdAt(), original.kind(),
            original.benchmarkClass(), original.benchmarkMethod(), original.mode(),
            original.score(), original.scoreError(), original.scoreUnit(),
            Map.of(
                "gc.alloc.rate", new SecondaryMetric(1234.5, null),
                "gc.alloc.rate.norm", new SecondaryMetric(42.0, "B/op")),
            original.jcstress(), original.tags(),
            original.resultPath(), original.resultJsonKey(), original.environmentJsonKey(),
            original.profilerOutputPath());

        var item = MeasurementItemMapper.toItem(withNullUnit);

        var secondaryMetrics = item.get("secondaryMetrics").m();
        assertThat(secondaryMetrics).doesNotContainKey("gc.alloc.rate");
        assertThat(secondaryMetrics).containsKey("gc.alloc.rate.norm");
    }

    /**
     * When every secondary metric is dropped, the attribute itself must be omitted rather than
     * stored as an empty map — matching the same "absent, not empty" convention the mapper already
     * applies elsewhere (see absentOptionalAttributesAreOmittedRatherThanStoredAsNull).
     */
    @Test
    void secondaryMetricsIsOmittedEntirelyWhenEveryEntryIsUnusable() {
        var original = StoredMeasurementFixtures.jmh();
        var allBad = new StoredMeasurement(
            original.project(), original.requestId(), original.createdAt(), original.kind(),
            original.benchmarkClass(), original.benchmarkMethod(), original.mode(),
            original.score(), original.scoreError(), original.scoreUnit(),
            Map.of("gc.alloc.rate", new SecondaryMetric(Double.NaN, "MB/sec")),
            original.jcstress(), original.tags(),
            original.resultPath(), original.resultJsonKey(), original.environmentJsonKey(),
            original.profilerOutputPath());

        var item = MeasurementItemMapper.toItem(allBad);

        assertThat(item).doesNotContainKey("secondaryMetrics");
        assertThat(MeasurementItemMapper.fromItem(item).secondaryMetrics()).isEmpty();
    }

    /**
     * design.md derives createdAt from OffsetDateTime.now(ZoneOffset.UTC), which on modern JVMs
     * carries sub-millisecond resolution. StoredMeasurement truncates createdAt to milliseconds on
     * construction (see StoredMeasurementTest), so a measurement built from such a clock still
     * round-trips even though the input Instant did not start out millisecond-precise.
     */
    @Test
    void aMeasurementBuiltFromANanosecondPrecisionClockStillRoundTrips() {
        var original = StoredMeasurementFixtures.jmh();
        var withNanosecondPrecisionClock = new StoredMeasurement(
            original.project(), original.requestId(),
            Instant.parse("2026-08-17T22:07:06.123456789Z"), original.kind(),
            original.benchmarkClass(), original.benchmarkMethod(), original.mode(),
            original.score(), original.scoreError(), original.scoreUnit(),
            original.secondaryMetrics(), original.jcstress(), original.tags(),
            original.resultPath(), original.resultJsonKey(), original.environmentJsonKey(),
            original.profilerOutputPath());

        assertThat(MeasurementItemMapper.fromItem(MeasurementItemMapper.toItem(withNanosecondPrecisionClock)))
            .isEqualTo(withNanosecondPrecisionClock);
    }
}
