package pl.wsztajerowski.baas.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class ResultKeysTest {

    @Test
    void partitionKeyIsPrefixedProject() {
        assertThat(ResultKeys.partitionKey("lynx-journal")).isEqualTo("RESULT#lynx-journal");
    }

    @Test
    void jmhSortKeyIsBenchmarkMajorThenChronological() {
        assertThat(ResultKeys.sortKey(StoredMeasurementFixtures.jmh())).isEqualTo(
            "pl.wsztajerowski.fake.Incrementing_Synchronized"
                + "#incrementUsingSynchronized"
                + "#2026-08-17T22:07:06.123Z"
                + "#jmh-20260817_220706");
    }

    @Test
    void jcstressSortKeyUsesAFixedPrefixBecauseThereIsNoBenchmarkMethod() {
        assertThat(ResultKeys.sortKey(StoredMeasurementFixtures.jcstress()))
            .isEqualTo("JCSTRESS#2026-08-17T22:15:00.000Z#jcstress-20260817_221500");
    }

    @Test
    void theRequestIdIndexIsKeyedOnRequestIdThenBenchmark() {
        var m = StoredMeasurementFixtures.jmh();

        assertThat(ResultKeys.requestIndexPartitionKey(m.requestId())).isEqualTo("jmh-20260817_220706");
        assertThat(ResultKeys.requestIndexSortKey(m))
            .isEqualTo("pl.wsztajerowski.fake.Incrementing_Synchronized#incrementUsingSynchronized");
    }

    @Test
    void formattedTimestampsAreAlwaysTheSameWidth() {
        assertThat(ResultKeys.formatTimestamp(Instant.parse("2026-01-01T00:00:00Z")))
            .hasSameSizeAs(ResultKeys.formatTimestamp(Instant.parse("2026-12-31T23:59:59.999Z")));
    }

    /** Instant.toString() drops trailing zero fractions — that is exactly the bug this guards. */
    @Test
    void aWholeSecondStillCarriesThreeFractionalDigits() {
        assertThat(ResultKeys.formatTimestamp(Instant.parse("2026-01-01T00:00:00Z")))
            .isEqualTo("2026-01-01T00:00:00.000Z");
    }

    @Test
    void lexicographicOrderEqualsChronologicalOrderAcrossMonthAndYearBoundaries() {
        List<Instant> chronological = List.of(
            Instant.parse("2025-12-31T23:59:59.998Z"),
            Instant.parse("2025-12-31T23:59:59.999Z"),
            Instant.parse("2026-01-01T00:00:00.000Z"),
            Instant.parse("2026-01-31T23:59:59.999Z"),
            Instant.parse("2026-02-01T00:00:00.000Z"),
            Instant.parse("2026-09-30T12:00:00.500Z"),
            Instant.parse("2026-10-01T12:00:00.500Z"));

        List<String> formatted = chronological.stream().map(ResultKeys::formatTimestamp).toList();

        assertThat(formatted).isSorted();
    }

    @Test
    void lexicographicOrderEqualsChronologicalOrderForRandomInstants() {
        var random = new Random(20260817L);
        var instants = new ArrayList<Instant>();
        for (int i = 0; i < 500; i++) {
            instants.add(Instant.ofEpochMilli(Math.abs(random.nextLong()) % 4_102_444_800_000L));
        }
        instants.sort(Instant::compareTo);

        assertThat(instants.stream().map(ResultKeys::formatTimestamp).toList()).isSorted();
    }

    @Test
    void timestampsAreRenderedInUtcRegardlessOfTheDefaultZone() {
        var original = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Kiritimati"));
            assertThat(ResultKeys.formatTimestamp(Instant.parse("2026-01-01T00:00:00Z")))
                .isEqualTo("2026-01-01T00:00:00.000Z");
        } finally {
            java.util.TimeZone.setDefault(original);
        }
    }
}
