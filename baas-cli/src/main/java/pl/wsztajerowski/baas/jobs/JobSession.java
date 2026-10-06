package pl.wsztajerowski.baas.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.baas.model.JobItem;
import pl.wsztajerowski.baas.model.JobStatus;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * One job's lifecycle as {@code baas run} drives it: reserve, launch, confirm, poll, and stop.
 * Written against two narrow interfaces so every path — including the ones that only a dead
 * network or a racing operator produce — is testable without AWS.
 *
 * <p>Every way the CLI stops a job goes through {@link #stop}: record why, then terminate, whether
 * or not the record landed. The shutdown hook, the poll cap and {@code baas jobs terminate} differ
 * only in the reason they record and in whether a failed termination is fatal.
 */
public final class JobSession {

    private static final Logger logger = LoggerFactory.getLogger(JobSession.class);

    /** The EC2 operations a job needs, nothing more. */
    public interface Instances {
        /** The instance's state name, or {@code "unknown"} when it cannot be described. */
        String state(String instanceId);

        /** The pending or running instance tagged with this job id, if there is one. */
        Optional<String> findLive(String jobId);

        /** Terminates the instance; throws when the request fails. */
        void terminate(String instanceId);
    }

    /** How a poll ended. {@code status} is a stored terminal status, or one of the three below. */
    public record Outcome(String status, int exitCode) {
        /** The instance is gone and no terminal status was recorded, nor any measurement stored. */
        public static final String VANISHED = JobStatus.VANISHED;
        /** The instance is gone and no terminal status was recorded, but measurements were stored. */
        public static final String STATUS_LOST = "status-lost";

        public boolean completed() {
            return JobStatus.COMPLETED.equals(status);
        }
    }

    /** What {@link #confirmLaunched} found. */
    public enum Confirmation { CONFIRMED, UNCONFIRMED, CANCELLED_WHILE_LAUNCHING }

    private final JobItem job;
    private final JobRecorder recorder;
    private final JobRecorder stopRecorder;
    private final Instances instances;

    private volatile String instanceId;
    private volatile boolean ended;
    private volatile String endStatus;

    /**
     * @param stopRecorder the recorder {@link #stop} writes through — one whose client gives up
     *                     quickly, so a dead network cannot hold a termination back
     */
    public JobSession(JobItem job, JobRecorder recorder, JobRecorder stopRecorder, Instances instances) {
        this.job = job;
        this.recorder = recorder;
        this.stopRecorder = stopRecorder;
        this.instances = instances;
    }

    public JobItem job() {
        return job;
    }

    public String instanceId() {
        return instanceId;
    }

    /**
     * The status the job ended with as this CLI last saw it: a stored terminal status, or
     * {@code vanished}/{@code status-lost} when the poll computed one. {@code null} while the job is
     * still going, or when the CLI never learned an outcome.
     */
    public String endStatus() {
        return endStatus;
    }

    /** Whether the job has reached an outcome this CLI no longer needs to act on. */
    public boolean ended() {
        return ended;
    }

    /** Throws when the reservation cannot be written; the caller then launches nothing. */
    public void reserve() {
        recorder.reserve(job);
    }

    /**
     * Records the instance the launch produced. The id is held before anything is written, so an
     * interrupt from here on can terminate it. A failed write is not fatal — the instance's own
     * {@code running} write supplies the id — but a write refused because the job already ended
     * (cancelled while launching) means nobody wants this instance, so it is terminated.
     */
    public Confirmation confirmLaunched(String launchedInstanceId) {
        this.instanceId = launchedInstanceId;
        JobRecorder.Write write;
        try {
            write = recorder.launched(job, launchedInstanceId);
        } catch (RuntimeException e) {
            logger.warn("Could not record the launch of {} ({}); the instance records itself once it boots.",
                launchedInstanceId, e.getMessage());
            return Confirmation.UNCONFIRMED;
        }
        if (write == JobRecorder.Write.WRITTEN) {
            return Confirmation.CONFIRMED;
        }
        Optional<JobItem> current = readQuietly();
        // An outcome only the instance writes means it got there first — RunInstances answered
        // after the whole job (seen live on a slow link) — not that the job was stopped. The poll
        // reports it, and the instance terminates itself after its boot-log upload.
        if (current.isPresent() && JobStatus.isRecordedByInstance(current.get().status())) {
            logger.info("Job {} already ended ({}) before its launch was confirmed.",
                job.jobId(), current.get().status());
            return Confirmation.CONFIRMED;
        }
        if (current.isPresent() && current.get().isTerminal()) {
            logger.error("Job {} was stopped ({}) while it was launching; terminating {}.",
                job.jobId(), current.get().status(), launchedInstanceId);
            ended = true;
            endStatus = current.get().status();
            terminateQuietly(launchedInstanceId);
            return Confirmation.CANCELLED_WHILE_LAUNCHING;
        }
        // Refused because the instance already recorded `running`: the launch is confirmed.
        return Confirmation.CONFIRMED;
    }

    /** Best effort: the caller reports the launch error whether or not this lands. */
    public void recordLaunchFailed(String errorCode) {
        ended = true;
        endStatus = JobStatus.LAUNCH_FAILED;
        try {
            recorder.launchFailed(job, errorCode);
        } catch (RuntimeException e) {
            logger.warn("Could not record the failed launch on the job item: {}", e.getMessage());
        }
    }

    /**
     * Records why the CLI is stopping the job, then terminates its instance — whatever the write
     * did, with one exception: a write refused because the instance already recorded its own
     * outcome ({@code completed} or {@code failed:<n>}) leaves the instance alone, since it is
     * uploading its boot log and terminates itself. Never throws: it runs from the shutdown hook and
     * the poll cap, where nothing is left to handle an exception.
     *
     * <p>When there is no instance id yet — an interrupt while {@code RunInstances} is in flight —
     * the instance is looked up by its job-id tag, which may not be visible yet. A miss is covered
     * on the instance: its {@code running} write is refused over the recorded outcome, and it
     * terminates itself without starting the benchmark.
     *
     * @return the status the job ends with: {@code reason}, unless it already had one
     */
    public String stop(String reason) {
        if (ended) {
            return reason;
        }
        ended = true;
        endStatus = stopAndTerminate(reason);
        return endStatus;
    }

    private String stopAndTerminate(String reason) {
        String standing = reason;
        if (writeStopQuietly(reason) == JobRecorder.Write.REFUSED) {
            Optional<JobItem> current = readQuietly(stopRecorder);
            if (current.isPresent() && current.get().isTerminal()) {
                standing = current.get().status();
                if (JobStatus.isRecordedByInstance(standing)) {
                    logger.info("Job {} already ended ({}); its instance terminates itself.", job.jobId(), standing);
                    return standing;
                }
            }
        }
        String target = instanceId != null ? instanceId : findLiveQuietly().orElse(null);
        if (target == null) {
            logger.info("Job {}: no instance to terminate.", job.jobId());
            return standing;
        }
        logger.info("Terminating instance {} ...", target);
        terminateQuietly(target);
        return standing;
    }

    /**
     * Polls until the job reaches an outcome or {@code capSeconds} pass.
     *
     * @param measurementsStored whether the job stored any measurement, asked only when the
     *                           instance is gone without a terminal status
     * @param progress           receives the instance state and the elapsed seconds on every poll
     *                           that has not ended
     */
    public Outcome await(int capSeconds, long pollMillis, LongSupplier clockMillis, Sleeper sleeper,
                         BooleanSupplier measurementsStored, BiConsumer<String, Long> progress)
        throws InterruptedException {
        long start = clockMillis.getAsLong();
        while (true) {
            long elapsedSeconds = (clockMillis.getAsLong() - start) / 1000;
            if (elapsedSeconds > capSeconds) {
                // The instance may have recorded its outcome during the last sleep.
                Optional<JobItem> last = readQuietly();
                if (last.isPresent() && last.get().isTerminal()) {
                    return finish(last.get());
                }
                logger.error("Client-side poll cap exceeded ({}s); recording the job as timed out.", capSeconds);
                String status = stop(JobStatus.TIMED_OUT);
                return new Outcome(status, JobStatus.COMPLETED.equals(status) ? 0 : 1);
            }

            Optional<JobItem> current = readQuietly();
            if (current.isPresent() && current.get().isTerminal()) {
                return finish(current.get());
            }

            String state = instanceId == null ? "unknown" : instances.state(instanceId);
            if ("terminated".equals(state) || "shutting-down".equals(state)) {
                ended = true;
                // The final status is written moments before the instance terminates; a poll
                // landing in between sees a dead instance and no status yet.
                Optional<JobItem> late = readQuietly();
                if (late.isPresent() && late.get().isTerminal()) {
                    return finish(late.get());
                }
                endStatus = measurementsStored.getAsBoolean() ? Outcome.STATUS_LOST : Outcome.VANISHED;
                return new Outcome(endStatus, 1);
            }
            progress.accept(state, elapsedSeconds);
            sleeper.sleep(pollMillis);
        }
    }

    /**
     * A terminal status read from the item. A {@code cancelled} or {@code timed-out} that this CLI
     * did not write came from elsewhere — another operator's {@code baas jobs terminate} — and
     * guarantees nothing about the instance, so a live one is terminated here. A {@code completed}
     * or {@code failed:<n>} was written by the instance itself, which terminates on its own after
     * uploading its boot log; terminating it from here could cut that upload off.
     */
    private Outcome finish(JobItem item) {
        ended = true;
        String status = item.status();
        endStatus = status;
        if ((JobStatus.CANCELLED.equals(status) || JobStatus.TIMED_OUT.equals(status)) && instanceId != null) {
            String state = instances.state(instanceId);
            if ("pending".equals(state) || "running".equals(state)) {
                logger.info("Job {} was {} elsewhere; terminating {}.", job.jobId(), status, instanceId);
                terminateQuietly(instanceId);
            }
        }
        return new Outcome(status, JobStatus.COMPLETED.equals(status) ? 0 : 1);
    }

    /** The write's result, or {@code null} when it failed. */
    private JobRecorder.Write writeStopQuietly(String reason) {
        try {
            JobRecorder.Write write = stopRecorder.stop(job, reason);
            if (write == JobRecorder.Write.REFUSED) {
                logger.debug("Job {} already had an outcome; {} was not recorded.", job.jobId(), reason);
            }
            return write;
        } catch (RuntimeException e) {
            logger.warn("Could not record {} on the job item ({}); terminating anyway.", reason, e.getMessage());
            return null;
        }
    }

    private Optional<JobItem> readQuietly() {
        return readQuietly(recorder);
    }

    private Optional<JobItem> readQuietly(JobRecorder from) {
        try {
            return from.read(job);
        } catch (RuntimeException e) {
            logger.debug("Could not read the job item: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> findLiveQuietly() {
        try {
            return instances.findLive(job.jobId());
        } catch (RuntimeException e) {
            logger.warn("Could not look up the instance of job {}: {}", job.jobId(), e.getMessage());
            return Optional.empty();
        }
    }

    private void terminateQuietly(String target) {
        try {
            instances.terminate(target);
        } catch (RuntimeException e) {
            logger.error("Failed to terminate instance {} ({}). The watchdog terminates it at its bound; "
                + "or run: baas jobs terminate {}", target, e.getMessage(), job.jobId());
        }
    }

    /** {@link Thread#sleep}, replaceable in tests. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
