package pl.wsztajerowski.baas.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JobStatusTest {

    @Test
    void everyOutcomeIsTerminal() {
        assertThat(JobStatus.COMPLETED).matches(JobStatus::isTerminal);
        assertThat(JobStatus.TIMED_OUT).matches(JobStatus::isTerminal);
        assertThat(JobStatus.CANCELLED).matches(JobStatus::isTerminal);
        assertThat(JobStatus.LAUNCH_FAILED).matches(JobStatus::isTerminal);
        assertThat(JobStatus.failed(3)).isEqualTo("failed:3").matches(JobStatus::isTerminal);
        assertThat(JobStatus.failed(137)).matches(JobStatus::isTerminal);
    }

    @Test
    void progressIsNotTerminal() {
        assertThat(JobStatus.LAUNCHING).matches(s -> !JobStatus.isTerminal(s)).matches(JobStatus::isInFlight);
        assertThat(JobStatus.LAUNCHED).matches(s -> !JobStatus.isTerminal(s)).matches(JobStatus::isInFlight);
        assertThat(JobStatus.RUNNING).matches(s -> !JobStatus.isTerminal(s)).matches(JobStatus::isInFlight);
    }

    /**
     * The CLI leaves a live instance alone after these: the instance wrote the first two and its
     * watchdog the third, and each is followed by the instance's own boot-log upload and termination.
     * Only {@code cancelled} makes the CLI terminate.
     */
    @Test
    void completedFailedAndTimedOutEndThemselves() {
        assertThat(JobStatus.endsItself(JobStatus.COMPLETED)).isTrue();
        assertThat(JobStatus.endsItself(JobStatus.failed(3))).isTrue();
        assertThat(JobStatus.endsItself(JobStatus.TIMED_OUT)).isTrue();
        assertThat(JobStatus.endsItself(JobStatus.CANCELLED)).isFalse();
        assertThat(JobStatus.endsItself(JobStatus.LAUNCH_FAILED)).isFalse();
        assertThat(JobStatus.endsItself(null)).isFalse();
    }

    /** Vanished is computed, never stored, so it is neither an outcome a write guards nor progress. */
    @Test
    void vanishedAndAbsentAreNeither() {
        assertThat(JobStatus.isTerminal(JobStatus.VANISHED)).isFalse();
        assertThat(JobStatus.isInFlight(JobStatus.VANISHED)).isFalse();
        assertThat(JobStatus.isTerminal(null)).isFalse();
        assertThat(JobStatus.isInFlight(null)).isFalse();
    }
}
