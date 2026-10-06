package pl.wsztajerowski.baas.jobs;

import pl.wsztajerowski.baas.model.JobItem;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The job item's reads and writes. Every write is one conditional {@code UpdateItem}: none of them
 * replaces a terminal status, so whichever outcome lands first is the one recorded. Only
 * {@link #reserve} creates an item; every later write requires it to exist.
 */
public interface JobRecorder {

    /** Whether a conditional write landed, or was refused by its condition. */
    enum Write { WRITTEN, REFUSED }

    /**
     * Records {@code launching} with every identity field. Throws when it cannot: a job that cannot
     * be recorded is not launched.
     */
    void reserve(JobItem job);

    /** {@code launched}, with the instance id, and only while the job is still {@code launching}. */
    Write launched(JobItem job, String instanceId);

    /** {@code launch-failed}, with the AWS error code. */
    Write launchFailed(JobItem job, String errorCode);

    /** A terminal status written by the CLI: {@code cancelled} or {@code timed-out}. */
    Write stop(JobItem job, String status);

    /** The job item as it stands now, read with strong consistency. */
    Optional<JobItem> read(JobItem job);

    /** The job item for a job id, through the job-ID index. */
    Optional<JobItem> find(String jobId);

    /**
     * The newest jobs first, keeping those {@code filter} accepts, until {@code limit} have been
     * kept or the jobs are exhausted.
     */
    List<JobItem> newestFirst(Predicate<JobItem> filter, int limit);
}
