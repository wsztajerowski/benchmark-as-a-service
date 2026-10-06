package pl.wsztajerowski.baas.jobs;

import pl.wsztajerowski.baas.model.JobItem;
import pl.wsztajerowski.baas.model.JobStatus;

import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What {@code baas jobs list} shows for a job, and which jobs it shows. A stored status that is
 * not terminal is only a claim: the job is in flight while an instance tagged with its id is
 * pending or running, and vanished otherwise. That is decided here, from one
 * {@code DescribeInstances} of the live runners, and never written back.
 */
public final class JobListing {

    /** One row: the job and its status as resolved now. */
    public record Row(JobItem job, String status, String liveInstanceId) {
        public boolean inFlight() {
            return JobStatus.isInFlight(status);
        }
    }

    private JobListing() {}

    /**
     * @param liveByJobId the instance id of every pending or running runner, keyed by the job id
     *                    on its tag
     */
    public static Row resolve(JobItem job, Map<String, String> liveByJobId) {
        String live = liveByJobId.get(job.jobId());
        if (job.isTerminal()) {
            return new Row(job, job.status(), live);
        }
        return new Row(job, live != null ? job.status() : JobStatus.VANISHED, live);
    }

    /**
     * The filter a listing pages with. {@code project} and {@code tags} are exact matches, every
     * named tag required; {@code inFlightOnly} keeps jobs whose instance is live.
     */
    public static Predicate<JobItem> filter(String project, Map<String, String> tags, boolean inFlightOnly,
                                            Map<String, String> liveByJobId) {
        return job -> (project == null || project.equals(job.project()))
            && tags.entrySet().stream().allMatch(t -> t.getValue().equals(job.tags().get(t.getKey())))
            && (!inFlightOnly || resolve(job, liveByJobId).inFlight());
    }

    /** The statuses a listing can show, for help text and documentation. */
    public static final Set<String> SHOWN_STATUSES = Set.of(
        JobStatus.LAUNCHING, JobStatus.LAUNCHED, JobStatus.RUNNING, JobStatus.COMPLETED,
        JobStatus.FAILED_PREFIX + "<n>", JobStatus.TIMED_OUT, JobStatus.CANCELLED,
        JobStatus.LAUNCH_FAILED, JobStatus.VANISHED);
}
