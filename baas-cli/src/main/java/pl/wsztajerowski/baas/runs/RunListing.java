package pl.wsztajerowski.baas.runs;

import pl.wsztajerowski.baas.model.RunItem;
import pl.wsztajerowski.baas.model.RunStatus;

import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What {@code baas runs list} shows for a run, and which runs it shows. A stored status that is
 * not terminal is only a claim: the run is in flight while an instance tagged with its id is
 * pending or running, and vanished otherwise. That is decided here, from one
 * {@code DescribeInstances} of the live runners, and never written back.
 */
public final class RunListing {

    /** One row: the run and its status as resolved now. */
    public record Row(RunItem run, String status, String liveInstanceId) {
        public boolean inFlight() {
            return RunStatus.isInFlight(status);
        }
    }

    private RunListing() {}

    /**
     * @param liveByRunId the instance id of every pending or running runner, keyed by the run id
     *                    on its tag
     */
    public static Row resolve(RunItem run, Map<String, String> liveByRunId) {
        String live = liveByRunId.get(run.runId());
        if (run.isTerminal()) {
            return new Row(run, run.status(), live);
        }
        return new Row(run, live != null ? run.status() : RunStatus.VANISHED, live);
    }

    /**
     * The filter a listing pages with. {@code project} and {@code tags} are exact matches, every
     * named tag required; {@code inFlightOnly} keeps runs whose instance is live.
     */
    public static Predicate<RunItem> filter(String project, Map<String, String> tags, boolean inFlightOnly,
                                            Map<String, String> liveByRunId) {
        return run -> (project == null || project.equals(run.project()))
            && tags.entrySet().stream().allMatch(t -> t.getValue().equals(run.tags().get(t.getKey())))
            && (!inFlightOnly || resolve(run, liveByRunId).inFlight());
    }

    /** The statuses a listing can show, for help text and documentation. */
    public static final Set<String> SHOWN_STATUSES = Set.of(
        RunStatus.LAUNCHING, RunStatus.LAUNCHED, RunStatus.RUNNING, RunStatus.COMPLETED,
        RunStatus.FAILED_PREFIX + "<n>", RunStatus.TIMED_OUT, RunStatus.CANCELLED,
        RunStatus.LAUNCH_FAILED, RunStatus.VANISHED);
}
