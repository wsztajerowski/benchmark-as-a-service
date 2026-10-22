package pl.wsztajerowski.baas.jobs;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InstanceStateTest {

    @Test
    void everyEc2StateNameIsRead() {
        assertThat(InstanceState.of("pending")).isEqualTo(InstanceState.PENDING);
        assertThat(InstanceState.of("running")).isEqualTo(InstanceState.RUNNING);
        assertThat(InstanceState.of("shutting-down")).isEqualTo(InstanceState.SHUTTING_DOWN);
        assertThat(InstanceState.of("terminated")).isEqualTo(InstanceState.TERMINATED);
        assertThat(InstanceState.of("stopping")).isEqualTo(InstanceState.STOPPING);
        assertThat(InstanceState.of("stopped")).isEqualTo(InstanceState.STOPPED);
    }

    @Test
    void anythingElseIsUnknown() {
        assertThat(InstanceState.of(null)).isEqualTo(InstanceState.UNKNOWN);
        assertThat(InstanceState.of("rebooting")).isEqualTo(InstanceState.UNKNOWN);
    }

    @Test
    void onlyPendingAndRunningAreLive() {
        for (InstanceState state : InstanceState.values()) {
            assertThat(state.isLive()).as(state.label())
                .isEqualTo(state == InstanceState.PENDING || state == InstanceState.RUNNING);
        }
    }

    /** What the poll has always treated as a dead runner; stopping and stopped are not runner states. */
    @Test
    void onlyShuttingDownAndTerminatedHaveEnded() {
        for (InstanceState state : InstanceState.values()) {
            assertThat(state.hasEnded()).as(state.label())
                .isEqualTo(state == InstanceState.SHUTTING_DOWN || state == InstanceState.TERMINATED);
        }
    }

    /** A failed describe must not end a poll, cut a session, or be terminated. */
    @Test
    void unknownIsNeitherLiveNorEnded() {
        assertThat(InstanceState.UNKNOWN.isLive()).isFalse();
        assertThat(InstanceState.UNKNOWN.hasEnded()).isFalse();
    }

    @Test
    void theLiveNamesAreWhatTheServerSideFilterSends() {
        assertThat(InstanceState.liveNames()).containsExactly("pending", "running");
    }

    @Test
    void theLabelIsEc2sSpelling() {
        assertThat(InstanceState.SHUTTING_DOWN.label()).isEqualTo("shutting-down");
        assertThat(InstanceState.RUNNING.label()).isEqualTo("running");
    }
}
