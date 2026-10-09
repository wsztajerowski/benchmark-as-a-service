package pl.wsztajerowski.baas.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JobIdTest {

    @Test
    void hasFixedWidth() {
        assertThat(JobId.generate()).hasSize(JobId.LENGTH);
        assertThat(JobId.generate(Instant.parse("2026-01-01T00:00:00Z"))).hasSize(JobId.LENGTH);
    }

    @Test
    void usesAnAlphabetThatCannotCorruptASortKey() {
        String id = JobId.generate();
        assertThat(id).matches("[0-9A-Za-z-]+");
        assertThat(id).doesNotContain(ResultKeys.SEPARATOR).doesNotContain("/");
    }

    @Test
    void lexicographicOrderEqualsChronologicalOrder() {
        List<Instant> chronological = List.of(
            Instant.parse("2025-12-31T23:59:59.999Z"),
            Instant.parse("2026-01-01T00:00:00.000Z"),
            Instant.parse("2026-01-31T23:59:59.999Z"),
            Instant.parse("2026-02-01T00:00:00.000Z"));

        List<String> ids = new ArrayList<>(chronological.stream().map(JobId::generate).toList());
        List<String> sorted = new ArrayList<>(ids);
        sorted.sort(String::compareTo);

        assertThat(sorted).isEqualTo(ids);
    }

    @Test
    void twoIdsFromTheSameInstantDiffer() {
        Instant fixed = Instant.parse("2026-08-20T17:44:32.812Z");
        assertThat(JobId.generate(fixed)).isNotEqualTo(JobId.generate(fixed));
    }

    @Test
    void encodesTheInstantAtMillisecondPrecision() {
        assertThat(JobId.generate(Instant.parse("2026-08-20T17:44:32.812Z")))
            .startsWith("20260820T174432812Z-");
    }

    @Test
    void leavesSortKeyFieldCountUnchanged() {
        String sk = "com.example.Bench" + ResultKeys.SEPARATOR + "method"
            + ResultKeys.SEPARATOR + "thrpt"
            + ResultKeys.SEPARATOR + "2026-08-20T17:44:32.812Z"
            + ResultKeys.SEPARATOR + JobId.generate();
        assertThat(sk.split(ResultKeys.SEPARATOR)).hasSize(5);
    }

    @Test
    void theDeclaredLengthMatchesTheFormat() {
        assertThat(JobId.LENGTH).isEqualTo(28);
        assertThat(JobId.generate(Instant.parse("2026-08-20T17:44:32.812Z")))
            .hasSize(JobId.LENGTH);
    }
}
