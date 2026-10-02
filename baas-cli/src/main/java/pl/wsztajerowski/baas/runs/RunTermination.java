package pl.wsztajerowski.baas.runs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.baas.model.RunItem;
import pl.wsztajerowski.baas.model.RunStatus;

import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * {@code baas runs terminate}: record {@code cancelled}, then stop the run's instance. Unlike the
 * shutdown hook it reports a failed termination — exiting zero over a live instance would hide it
 * from {@code --in-flight} — and it stops a live instance whatever the item says, so a retry after
 * a failed termination still works.
 */
public final class RunTermination {

    private static final Logger logger = LoggerFactory.getLogger(RunTermination.class);

    private final RunRecorder recorder;
    private final RunRecorder stopRecorder;
    private final RunSession.Instances instances;

    public RunTermination(RunRecorder recorder, RunRecorder stopRecorder, RunSession.Instances instances) {
        this.recorder = recorder;
        this.stopRecorder = stopRecorder;
        this.instances = instances;
    }

    /**
     * @param confirm asked once the run and its instance are known; false aborts without a change
     * @return the exit code
     */
    public int terminate(String runId, BooleanSupplier confirm) {
        Optional<RunItem> found = recorder.find(runId);
        if (found.isEmpty()) {
            logger.error("No run found with id '{}'. List runs with: baas runs list", runId);
            return 1;
        }
        RunItem run = found.get();
        String live = liveInstance(run);
        if (run.isTerminal() && live == null) {
            logger.info("Run {} already ended ({}); nothing to terminate.", runId, run.status());
            return 0;
        }
        if (!confirm.getAsBoolean()) {
            logger.info("Nothing was changed.");
            return 1;
        }
        if (!run.isTerminal()) {
            try {
                stopRecorder.stop(run, RunStatus.CANCELLED);
            } catch (RuntimeException e) {
                logger.warn("Could not record the cancellation ({}); terminating anyway.", e.getMessage());
            }
        }
        if (live == null) {
            logger.info("Run {} recorded as cancelled; it has no live instance.", runId);
            return 0;
        }
        try {
            instances.terminate(live);
        } catch (RuntimeException e) {
            logger.error("Failed to terminate instance {} of run {}: {}", live, runId, e.getMessage());
            return 1;
        }
        logger.info("Terminated instance {} of run {}.", live, runId);
        return 0;
    }

    /** The run's pending or running instance: the recorded one if live, else whatever carries its tag. */
    private String liveInstance(RunItem run) {
        if (run.instanceId() != null) {
            String state = instances.state(run.instanceId());
            if ("pending".equals(state) || "running".equals(state)) {
                return run.instanceId();
            }
        }
        return instances.findLive(run.runId()).orElse(null);
    }
}
