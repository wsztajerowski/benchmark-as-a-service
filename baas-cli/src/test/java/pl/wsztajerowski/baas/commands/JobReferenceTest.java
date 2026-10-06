package pl.wsztajerowski.baas.commands;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JobReferenceTest {

    private final List<String> lookedUp = new ArrayList<>();

    private String lookup(String jobId) {
        lookedUp.add(jobId);
        return jobId.equals("20261002T080250645Z-264f5dfb")
            ? "jobs/baas-lifecycle-test/20261002T080250645Z-264f5dfb" : null;
    }

    @Test
    void aJobIdResolvesToItsStoredPath() {
        assertThat(JobReference.resolve("20261002T080250645Z-264f5dfb", this::lookup))
            .isEqualTo("jobs/baas-lifecycle-test/20261002T080250645Z-264f5dfb");
    }

    /** Both path shapes pass through untouched, and never reach the table. */
    @Test
    void aPathIsUsedAsGivenWithoutALookup() {
        assertThat(JobReference.resolve("jobs/p/20261002T080250645Z-264f5dfb", this::lookup))
            .isEqualTo("jobs/p/20261002T080250645Z-264f5dfb");
        assertThat(JobReference.resolve("main/jmh/20260819_090000", this::lookup))
            .isEqualTo("main/jmh/20260819_090000");
        assertThat(lookedUp).isEmpty();
    }

    @Test
    void aTrailingSlashIsTolerated() {
        assertThat(JobReference.resolve("jobs/p/20261002T080250645Z-264f5dfb/", this::lookup))
            .isEqualTo("jobs/p/20261002T080250645Z-264f5dfb");
    }

    /**
     * Only a job from before job items that stored no measurement has no index entry; every later
     * run resolves through its job item, so the message points at the job list first.
     */
    @Test
    void anUnknownJobIdResolvesToNothingAndTheMessageNamesTheWayOut() {
        assertThat(JobReference.resolve("20261002T000000000Z-00000000", this::lookup)).isNull();
        assertThat(JobReference.noSuchJob("20261002T000000000Z-00000000"))
            .contains("20261002T000000000Z-00000000", "baas jobs list", "jobs/<project>/<jobId>");
    }
}
