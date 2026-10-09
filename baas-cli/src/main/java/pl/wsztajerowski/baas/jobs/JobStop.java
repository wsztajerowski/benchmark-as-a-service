package pl.wsztajerowski.baas.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.baas.model.JobItem;
import pl.wsztajerowski.baas.model.JobStatus;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The one way the CLI stops a job: record why, then terminate its instance whether or not the
 * record landed — unless the record was refused because the job had already reached a status after
 * which the instance ends itself. {@link JobSession#stop} (the shutdown hook, the poll cap) and
 * {@code baas jobs terminate} both come through here, so the rule cannot drift between them.
 */
final class JobStop {

    private static final Logger logger = LoggerFactory.getLogger(JobStop.class);

    /** What the stop did with the instance. */
    enum Action { LEFT_ALONE, NO_INSTANCE, TERMINATED, TERMINATE_FAILED }

    /**
     * @param status  the status the job ends with: {@code reason}, unless it already had one
     * @param target  the instance acted on, or {@code null}
     * @param failure why the termination request failed, for {@link Action#TERMINATE_FAILED}
     */
    record Result(String status, Action action, String target, RuntimeException failure) {}

    private JobStop() {}

    /**
     * Never throws: the shutdown hook and the poll cap have nothing left to handle an exception, and
     * {@code jobs terminate} reports a {@link Action#TERMINATE_FAILED} itself.
     *
     * @param recorder  a recorder whose writes give up quickly, so a dead network cannot hold the
     *                  termination back; its reads are strongly consistent
     * @param target    the instance to terminate, resolved only once the write is settled
     */
    static Result stop(JobItem job, String reason, JobRecorder recorder, JobSession.Instances instances,
                       Supplier<Optional<String>> target) {
        String standing = reason;
        if (write(job, reason, recorder) == JobRecorder.Write.REFUSED) {
            Optional<JobItem> current = read(job, recorder);
            if (current.isPresent() && current.get().isTerminal()) {
                standing = current.get().status();
                if (JobStatus.endsItself(standing)) {
                    return new Result(standing, Action.LEFT_ALONE, null, null);
                }
            }
        }
        String instance;
        try {
            instance = target.get().orElse(null);
        } catch (RuntimeException e) {
            logger.warn("Could not look up the instance of job {}: {}", job.jobId(), e.getMessage());
            instance = null;
        }
        if (instance == null) {
            return new Result(standing, Action.NO_INSTANCE, null, null);
        }
        try {
            instances.terminate(instance);
            return new Result(standing, Action.TERMINATED, instance, null);
        } catch (RuntimeException e) {
            return new Result(standing, Action.TERMINATE_FAILED, instance, e);
        }
    }

    /** The write's result, or {@code null} when it failed. */
    private static JobRecorder.Write write(JobItem job, String reason, JobRecorder recorder) {
        try {
            JobRecorder.Write write = recorder.stop(job, reason);
            if (write == JobRecorder.Write.REFUSED) {
                logger.debug("Job {} already had an outcome; {} was not recorded.", job.jobId(), reason);
            }
            return write;
        } catch (RuntimeException e) {
            logger.warn("Could not record {} on the job item ({}); terminating anyway.", reason, e.getMessage());
            return null;
        }
    }

    private static Optional<JobItem> read(JobItem job, JobRecorder recorder) {
        try {
            return recorder.read(job);
        } catch (RuntimeException e) {
            logger.debug("Could not read the job item: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
