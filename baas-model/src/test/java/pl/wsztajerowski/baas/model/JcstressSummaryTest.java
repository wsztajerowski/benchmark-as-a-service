package pl.wsztajerowski.baas.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JcstressSummaryTest {

    @Test
    void aJcstressSummaryTreatsNullMapsAsEmptyRatherThanThrowing() {
        var summary = new JcstressSummary(5, 5, 0, 0, null, null, null);

        assertThat(summary.failed()).isEmpty();
        assertThat(summary.errors()).isEmpty();
        assertThat(summary.interesting()).isEmpty();
    }
}
