package pl.wsztajerowski.baas.runs;

import pl.wsztajerowski.baas.model.RunItem;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The run item's reads and writes. Every write is one conditional {@code UpdateItem}: none of them
 * replaces a terminal status, so whichever outcome lands first is the one recorded. Only
 * {@link #reserve} creates an item; every later write requires it to exist.
 */
public interface RunRecorder {

    /** Whether a conditional write landed, or was refused by its condition. */
    enum Write { WRITTEN, REFUSED }

    /**
     * Records {@code launching} with every identity field. Throws when it cannot: a run that cannot
     * be recorded is not launched.
     */
    void reserve(RunItem run);

    /** {@code launched}, with the instance id, and only while the run is still {@code launching}. */
    Write launched(RunItem run, String instanceId);

    /** {@code launch-failed}, with the AWS error code. */
    Write launchFailed(RunItem run, String errorCode);

    /** A terminal status written by the CLI: {@code cancelled} or {@code timed-out}. */
    Write stop(RunItem run, String status);

    /** The run item as it stands now, read with strong consistency. */
    Optional<RunItem> read(RunItem run);

    /** The run item for a run id, through the request-ID index. */
    Optional<RunItem> find(String runId);

    /**
     * The newest runs first, keeping those {@code filter} accepts, until {@code limit} have been
     * kept or the runs are exhausted.
     */
    List<RunItem> newestFirst(Predicate<RunItem> filter, int limit);
}
