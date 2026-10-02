package pl.wsztajerowski.baas.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunStatusTest {

    @Test
    void everyOutcomeIsTerminal() {
        assertThat(RunStatus.COMPLETED).matches(RunStatus::isTerminal);
        assertThat(RunStatus.TIMED_OUT).matches(RunStatus::isTerminal);
        assertThat(RunStatus.CANCELLED).matches(RunStatus::isTerminal);
        assertThat(RunStatus.LAUNCH_FAILED).matches(RunStatus::isTerminal);
        assertThat(RunStatus.failed(3)).isEqualTo("failed:3").matches(RunStatus::isTerminal);
        assertThat(RunStatus.failed(137)).matches(RunStatus::isTerminal);
    }

    @Test
    void progressIsNotTerminal() {
        assertThat(RunStatus.LAUNCHING).matches(s -> !RunStatus.isTerminal(s)).matches(RunStatus::isInFlight);
        assertThat(RunStatus.LAUNCHED).matches(s -> !RunStatus.isTerminal(s)).matches(RunStatus::isInFlight);
        assertThat(RunStatus.RUNNING).matches(s -> !RunStatus.isTerminal(s)).matches(RunStatus::isInFlight);
    }

    /** Vanished is computed, never stored, so it is neither an outcome a write guards nor progress. */
    @Test
    void vanishedAndAbsentAreNeither() {
        assertThat(RunStatus.isTerminal(RunStatus.VANISHED)).isFalse();
        assertThat(RunStatus.isInFlight(RunStatus.VANISHED)).isFalse();
        assertThat(RunStatus.isTerminal(null)).isFalse();
        assertThat(RunStatus.isInFlight(null)).isFalse();
    }
}
