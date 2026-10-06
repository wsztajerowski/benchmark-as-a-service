package pl.wsztajerowski.baas.jobs;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.model.JobItem;
import pl.wsztajerowski.baas.model.JobStatus;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JobListingTest {

    private static JobItem run(String id, String project, String status, String instanceId, Map<String, String> tags) {
        return new JobItem(id, project, Instant.parse("2026-10-03T00:00:00Z"), "jobs/" + project + "/" + id,
            "c5.2xlarge", status, instanceId, null, tags, null);
    }

    @Test
    void aStoredOutcomeIsShownAsIs() {
        var row = JobListing.resolve(run("r", "p", JobStatus.COMPLETED, "i-1", Map.of()), Map.of());

        assertThat(row.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(row.inFlight()).isFalse();
    }

    @Test
    void anInFlightStatusWithALiveInstanceIsInFlight() {
        var row = JobListing.resolve(run("r", "p", JobStatus.RUNNING, "i-1", Map.of()), Map.of("r", "i-1"));

        assertThat(row.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(row.inFlight()).isTrue();
    }

    @Test
    void anInFlightStatusWithNoLiveInstanceVanished() {
        var row = JobListing.resolve(run("r", "p", JobStatus.RUNNING, "i-1", Map.of()), Map.of());

        assertThat(row.status()).isEqualTo(JobStatus.VANISHED);
        assertThat(row.inFlight()).isFalse();
    }

    /** The CLI died before recording the instance; the tag still ties the job to it. */
    @Test
    void aLaunchingJobWithoutAnInstanceIdResolvesThroughTheTag() {
        var row = JobListing.resolve(run("r", "p", JobStatus.LAUNCHING, null, Map.of()), Map.of("r", "i-7"));

        assertThat(row.inFlight()).isTrue();
        assertThat(row.liveInstanceId()).isEqualTo("i-7");
    }

    @Test
    void inFlightKeepsOnlyJobsWithALiveInstance() {
        var live = Map.of("a", "i-1");
        var filter = JobListing.filter(null, Map.of(), true, live);

        assertThat(filter.test(run("a", "p", JobStatus.RUNNING, "i-1", Map.of()))).isTrue();
        assertThat(filter.test(run("b", "p", JobStatus.RUNNING, "i-2", Map.of()))).as("vanished").isFalse();
        assertThat(filter.test(run("c", "p", JobStatus.COMPLETED, "i-3", Map.of()))).isFalse();
    }

    @Test
    void projectAndEveryNamedTagMustMatch() {
        var filter = JobListing.filter("p", Map.of("source", "local", "branch", "main"), false, Map.of());

        assertThat(filter.test(run("a", "p", JobStatus.COMPLETED, null, Map.of("source", "local", "branch", "main")))).isTrue();
        assertThat(filter.test(run("b", "p", JobStatus.COMPLETED, null, Map.of("source", "local")))).isFalse();
        assertThat(filter.test(run("c", "q", JobStatus.COMPLETED, null, Map.of("source", "local", "branch", "main")))).isFalse();
    }

    /** exclude_from_results keeps fixture measurements out of comparisons; a job is an operation. */
    @Test
    void anExcludedJobIsListedLikeAnyOther() {
        var filter = JobListing.filter(null, Map.of(), false, Map.of());

        assertThat(filter.test(run("ci", "p", JobStatus.RUNNING, "i-1", Map.of("exclude_from_results", "true", "source", "ci"))))
            .isTrue();
    }
}
