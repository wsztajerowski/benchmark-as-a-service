package pl.wsztajerowski.baas.jobs;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.model.JobItem;
import pl.wsztajerowski.baas.model.JobStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JobListingTest {

    /** Long after every job below was created, so the vanish grace never applies unless a test says so. */
    private static final Instant LATER = Instant.parse("2026-10-04T00:00:00Z");
    private static final Instant CREATED = Instant.parse("2026-10-03T00:00:00Z");

    private static JobItem run(String id, String project, String status, String instanceId, Map<String, String> tags) {
        return new JobItem(id, project, Instant.parse("2026-10-03T00:00:00Z"), "jobs/" + project + "/" + id,
            "c5.2xlarge", status, instanceId, null, tags, null);
    }

    @Test
    void aStoredOutcomeIsShownAsIs() {
        var row = JobListing.resolve(run("r", "p", JobStatus.COMPLETED, "i-1", Map.of()), Map.of(), LATER);

        assertThat(row.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(row.inFlight()).isFalse();
    }

    @Test
    void anInFlightStatusWithALiveInstanceIsInFlight() {
        var row = JobListing.resolve(run("r", "p", JobStatus.RUNNING, "i-1", Map.of()), Map.of("r", "i-1"), LATER);

        assertThat(row.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(row.inFlight()).isTrue();
    }

    @Test
    void anInFlightStatusWithNoLiveInstanceVanished() {
        var row = JobListing.resolve(run("r", "p", JobStatus.RUNNING, "i-1", Map.of()), Map.of(), LATER);

        assertThat(row.status()).isEqualTo(JobStatus.VANISHED);
        assertThat(row.inFlight()).isFalse();
    }

    /** R12, U31: reserved seconds ago, its instance not requested or not visible yet. */
    @Test
    void aJobYoungerThanTheGraceKeepsItsStoredStatus() {
        var row = JobListing.resolve(run("r", "p", JobStatus.LAUNCHING, null, Map.of()), Map.of(),
            CREATED.plusSeconds(10));

        assertThat(row.status()).isEqualTo(JobStatus.LAUNCHING);
        assertThat(row.inFlight()).isTrue();
        assertThat(JobListing.filter(null, Map.of(), List.of(), true, Map.of(), CREATED.plusSeconds(10))
            .test(run("r", "p", JobStatus.LAUNCHING, null, Map.of()))).as("--in-flight shows it").isTrue();
    }

    /** A CLI killed mid-launch: once the grace has passed with no instance, the job vanished. */
    @Test
    void aJobOlderThanTheGraceWithNoInstanceVanished() {
        var row = JobListing.resolve(run("r", "p", JobStatus.LAUNCHING, null, Map.of()), Map.of(),
            CREATED.plus(java.time.Duration.ofMinutes(6)));

        assertThat(row.status()).isEqualTo(JobStatus.VANISHED);
    }

    /** The CLI died before recording the instance; the tag still ties the job to it. */
    @Test
    void aLaunchingJobWithoutAnInstanceIdResolvesThroughTheTag() {
        var row = JobListing.resolve(run("r", "p", JobStatus.LAUNCHING, null, Map.of()), Map.of("r", "i-7"), LATER);

        assertThat(row.inFlight()).isTrue();
        assertThat(row.liveInstanceId()).isEqualTo("i-7");
    }

    @Test
    void inFlightKeepsOnlyJobsWithALiveInstance() {
        var live = Map.of("a", "i-1");
        var filter = JobListing.filter(null, Map.of(), List.of(), true, live, LATER);

        assertThat(filter.test(run("a", "p", JobStatus.RUNNING, "i-1", Map.of()))).isTrue();
        assertThat(filter.test(run("b", "p", JobStatus.RUNNING, "i-2", Map.of()))).as("vanished").isFalse();
        assertThat(filter.test(run("c", "p", JobStatus.COMPLETED, "i-3", Map.of()))).isFalse();
    }

    @Test
    void projectAndEveryNamedTagMustMatch() {
        var filter = JobListing.filter("p", Map.of("source", "local", "branch", "main"), List.of(), false, Map.of(), LATER);

        assertThat(filter.test(run("a", "p", JobStatus.COMPLETED, null, Map.of("source", "local", "branch", "main")))).isTrue();
        assertThat(filter.test(run("b", "p", JobStatus.COMPLETED, null, Map.of("source", "local")))).isFalse();
        assertThat(filter.test(run("c", "q", JobStatus.COMPLETED, null, Map.of("source", "local", "branch", "main")))).isFalse();
    }

    /** exclude_from_results keeps fixture measurements out of comparisons; a job is an operation. */
    @Test
    void anExcludedJobIsListedLikeAnyOther() {
        var filter = JobListing.filter(null, Map.of(), List.of(), false, Map.of(), LATER);

        assertThat(filter.test(run("ci", "p", JobStatus.RUNNING, "i-1", Map.of("exclude_from_results", "true", "source", "ci"))))
            .isTrue();
    }

    private static JobListing.Row at(String id, String created, String status, String project) {
        var job = new JobItem(id, project, Instant.parse(created), "jobs/" + project + "/" + id, "c5.2xlarge",
            status, null, null, Map.of("source", "local"), null);
        return new JobListing.Row(job, status, null);
    }

    @Test
    void anExcludedTagDropsTheJob() {
        var filter = JobListing.filter(null, Map.of(), List.of("source=ci"), false, Map.of(), LATER);

        assertThat(filter.test(run("a", "p", JobStatus.COMPLETED, null, Map.of("source", "ci")))).isFalse();
        assertThat(filter.test(run("b", "p", JobStatus.COMPLETED, null, Map.of("source", "local")))).isTrue();
    }

    @Test
    void theDefaultOrderIsNewestFirstAndAscReversesIt() {
        var rows = List.of(at("old", "2026-10-01T00:00:00Z", "completed", "p"),
            at("new", "2026-10-03T00:00:00Z", "completed", "p"),
            at("mid", "2026-10-02T00:00:00Z", "completed", "p"));

        assertThat(JobListing.sorted(rows, "created", false)).extracting(r -> r.job().jobId())
            .containsExactly("new", "mid", "old");
        assertThat(JobListing.sorted(rows, "created", true)).extracting(r -> r.job().jobId())
            .containsExactly("old", "mid", "new");
    }

    @Test
    void sortingByStatusKeepsNewestFirstWithinAStatus() {
        var rows = List.of(at("a", "2026-10-01T00:00:00Z", "running", "p"),
            at("b", "2026-10-03T00:00:00Z", "completed", "p"),
            at("c", "2026-10-02T00:00:00Z", "completed", "p"));

        assertThat(JobListing.sorted(rows, "status", true)).extracting(r -> r.job().jobId())
            .containsExactly("b", "c", "a");
    }
}
