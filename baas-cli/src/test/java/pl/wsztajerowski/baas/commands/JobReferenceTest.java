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

    /** A path is not a job id any more: refused with a message, before any lookup. */
    @Test
    void aPathIsRefusedWithoutALookup() {
        assertThat(JobReference.notAJobId("jobs/p/20261002T080250645Z-264f5dfb"))
            .contains("is not a job id", "20260820T174432812Z-a3f9c21b");
        assertThat(JobReference.notAJobId("main/jmh/20260819_090000")).isNotNull();
        assertThat(JobReference.notAJobId("20261002T080250645Z-264f5dfb")).isNull();
        assertThat(lookedUp).isEmpty();
    }

    @Test
    void aStoredTrailingSlashIsDropped() {
        assertThat(JobReference.resolve("20261002T080250645Z-264f5dfb",
            id -> "jobs/p/20261002T080250645Z-264f5dfb/"))
            .isEqualTo("jobs/p/20261002T080250645Z-264f5dfb");
    }

    @Test
    void anUnknownJobIdResolvesToNothingAndTheMessageNamesTheWayOut() {
        assertThat(JobReference.resolve("20261002T000000000Z-00000000", this::lookup)).isNull();
        assertThat(JobReference.noSuchJob("20261002T000000000Z-00000000"))
            .contains("20261002T000000000Z-00000000", "baas jobs list")
            .doesNotContain("result path");
    }
}
