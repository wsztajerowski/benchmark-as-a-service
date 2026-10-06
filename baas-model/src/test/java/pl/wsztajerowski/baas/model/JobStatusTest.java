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

    /** Vanished is computed, never stored, so it is neither an outcome a write guards nor progress. */
    /** Only these leave the instance to terminate itself; the CLI stops it on any other outcome. */
    @Test
    void onlyCompletedAndFailedAreRecordedByTheInstance() {
        assertThat(JobStatus.isRecordedByInstance(JobStatus.COMPLETED)).isTrue();
        assertThat(JobStatus.isRecordedByInstance(JobStatus.failed(3))).isTrue();
        assertThat(JobStatus.isRecordedByInstance(JobStatus.CANCELLED)).isFalse();
        assertThat(JobStatus.isRecordedByInstance(JobStatus.TIMED_OUT)).isFalse();
        assertThat(JobStatus.isRecordedByInstance(JobStatus.LAUNCH_FAILED)).isFalse();
        assertThat(JobStatus.isRecordedByInstance(null)).isFalse();
    }

    @Test
    void vanishedAndAbsentAreNeither() {
        assertThat(JobStatus.isTerminal(JobStatus.VANISHED)).isFalse();
        assertThat(JobStatus.isInFlight(JobStatus.VANISHED)).isFalse();
        assertThat(JobStatus.isTerminal(null)).isFalse();
        assertThat(JobStatus.isInFlight(null)).isFalse();
    }
}
