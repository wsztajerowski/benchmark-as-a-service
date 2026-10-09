package pl.wsztajerowski.baas.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.baas.model.JobItem;
import pl.wsztajerowski.baas.model.JobStatus;

import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * {@code baas jobs terminate}: record {@code cancelled}, then stop the job's instance, through the
 * same {@link JobStop} the shutdown hook and the poll cap use. Unlike them it reports a failed
 * termination — exiting zero over a live instance would hide it from {@code --in-flight} — and it
 * stops a live instance of a job already {@code cancelled}, so a retry after a failed termination
 * still works. A status after which the instance ends itself leaves the instance alone.
 */
public final class JobTermination {

    private static final Logger logger = LoggerFactory.getLogger(JobTermination.class);

    private final JobRecorder recorder;
    private final JobRecorder stopRecorder;
    private final JobSession.Instances instances;

    public JobTermination(JobRecorder recorder, JobRecorder stopRecorder, JobSession.Instances instances) {
        this.recorder = recorder;
        this.stopRecorder = stopRecorder;
        this.instances = instances;
    }

    /**
     * @param confirm asked once the job and its instance are known; false aborts without a change
     * @return the exit code
     */
    public int terminate(String jobId, BooleanSupplier confirm) {
        // The index finds the job's key; the item is then read through the key, strongly, because
        // the index can lag a status the instance has just written.
        Optional<JobItem> found = recorder.find(jobId).flatMap(recorder::read);
        if (found.isEmpty()) {
            // No item in this deployment. Every runner this CLI launches is reserved first, so an id
            // without an item is another deployment's job, or none — never something to terminate.
            logger.error("No job found with id '{}'. List jobs with: baas jobs list", jobId);
            return 1;
        }
        JobItem job = found.get();
        String live = liveInstance(job);
        if (job.isTerminal() && live == null) {
            logger.info("Job {} already ended ({}); nothing to terminate.", jobId, job.status());
            return 0;
        }
        if (JobStatus.endsItself(job.status())) {
            logger.info(endedItself(job.jobId(), job.status(), live));
            return 0;
        }
        if (!confirm.getAsBoolean()) {
            logger.info("Nothing was changed.");
            return 1;
        }
        var result = JobStop.stop(job, JobStatus.CANCELLED, stopRecorder, instances,
            () -> Optional.ofNullable(live));
        return switch (result.action()) {
            case LEFT_ALONE -> {
                // The instance recorded its outcome between the read above and the cancel write.
                logger.info(endedItself(jobId, result.status(), live));
                yield 0;
            }
            case NO_INSTANCE -> {
                logger.info("Job {} recorded as {}; it has no live instance.", jobId, result.status());
                yield 0;
            }
            case TERMINATED -> {
                logger.info("Terminated instance {} of job {}.", result.target(), jobId);
                yield 0;
            }
            case TERMINATE_FAILED -> {
                logger.error("Failed to terminate instance {} of job {}: {}", result.target(), jobId,
                    result.failure().getMessage());
                yield 1;
            }
        };
    }

    private static String endedItself(String jobId, String status, String live) {
        return live == null
            ? "Job %s already ended (%s); nothing to terminate.".formatted(jobId, status)
            : "Job %s already ended (%s); instance %s uploads its boot log and terminates itself."
                .formatted(jobId, status, live);
    }

    /** The job's pending or running instance: the recorded one if live, else whatever carries its tag. */
    private String liveInstance(JobItem job) {
        if (job.instanceId() != null) {
            String state = instances.state(job.instanceId());
            if ("pending".equals(state) || "running".equals(state)) {
                return job.instanceId();
            }
        }
        return instances.findLive(job.jobId()).orElse(null);
    }
}
