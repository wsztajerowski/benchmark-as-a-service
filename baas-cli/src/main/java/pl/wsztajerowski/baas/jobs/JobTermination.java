package pl.wsztajerowski.baas.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.baas.model.JobItem;
import pl.wsztajerowski.baas.model.JobStatus;

import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * {@code baas jobs terminate}: record {@code cancelled}, then stop the job's instance. Unlike the
 * shutdown hook it reports a failed termination — exiting zero over a live instance would hide it
 * from {@code --in-flight} — and it stops a live instance whatever the item says, so a retry after
 * a failed termination still works.
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
        Optional<JobItem> found = recorder.find(jobId);
        if (found.isEmpty()) {
            return terminateUnrecorded(jobId, confirm);
        }
        JobItem job = found.get();
        String live = liveInstance(job);
        if (job.isTerminal() && live == null) {
            logger.info("Job {} already ended ({}); nothing to terminate.", jobId, job.status());
            return 0;
        }
        // The instance wrote this outcome itself and is uploading its boot log before it
        // terminates; cutting that off loses the one record of a failed job. The same rule
        // JobSession.stop follows.
        if (JobStatus.isRecordedByInstance(job.status())) {
            logger.info("Job {} already ended ({}); instance {} is uploading its boot log and "
                + "terminates itself.", jobId, job.status(), live);
            return 0;
        }
        if (!confirm.getAsBoolean()) {
            logger.info("Nothing was changed.");
            return 1;
        }
        if (!job.isTerminal()) {
            try {
                stopRecorder.stop(job, JobStatus.CANCELLED);
            } catch (RuntimeException e) {
                logger.warn("Could not record the cancellation ({}); terminating anyway.", e.getMessage());
            }
        }
        if (live == null) {
            logger.info("Job {} recorded as cancelled; it has no live instance.", jobId);
            return 0;
        }
        try {
            instances.terminate(live);
        } catch (RuntimeException e) {
            logger.error("Failed to terminate instance {} of job {}: {}", live, jobId, e.getMessage());
            return 1;
        }
        logger.info("Terminated instance {} of job {}.", live, jobId);
        return 0;
    }

    /**
     * A job with no item: launched by a CLI from before job items, whose instance carries the job-id
     * tag and nothing more. Teardown names such jobs from that tag and points at this command, so it
     * has to stop them. There is nothing to record.
     */
    private int terminateUnrecorded(String jobId, BooleanSupplier confirm) {
        Optional<String> live = instances.findLive(jobId);
        if (live.isEmpty()) {
            logger.error("No job found with id '{}'. List jobs with: baas jobs list", jobId);
            return 1;
        }
        if (!confirm.getAsBoolean()) {
            logger.info("Nothing was changed.");
            return 1;
        }
        try {
            instances.terminate(live.get());
        } catch (RuntimeException e) {
            logger.error("Failed to terminate instance {} of job {}: {}", live.get(), jobId, e.getMessage());
            return 1;
        }
        logger.info("Terminated instance {} of job {}, which has no job item to record it on.", live.get(), jobId);
        return 0;
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
