package pl.wsztajerowski.baas.model;

import java.util.List;

/**
 * The job item's status vocabulary, defined once: the CLI's conditional writes, user-data's
 * {@code job_status} and every reader classify a status through this class, so "terminal" cannot
 * mean one thing to the writer and another to the guard that protects it.
 *
 * <p>Stored as plain strings rather than an enum because {@code failed:<exitCode>} carries a value,
 * and because the shell writes them.
 */
public final class JobStatus {

    public static final String LAUNCHING = "launching";
    public static final String LAUNCHED = "launched";
    public static final String RUNNING = "running";
    public static final String COMPLETED = "completed";
    public static final String TIMED_OUT = "timed-out";
    public static final String CANCELLED = "cancelled";
    public static final String LAUNCH_FAILED = "launch-failed";
    public static final String FAILED_PREFIX = "failed:";

    /**
     * Never stored: a non-terminal status whose instance is gone. Computed at read time, because
     * writing it back would make a list command a writer and race an instance that is only slow
     * to report.
     */
    public static final String VANISHED = "vanished";

    /** The terminal statuses matched exactly; {@code failed:<n>} is matched by prefix. */
    public static final List<String> EXACT_TERMINAL = List.of(COMPLETED, TIMED_OUT, CANCELLED, LAUNCH_FAILED);

    private JobStatus() {}

    public static String failed(int exitCode) {
        return FAILED_PREFIX + exitCode;
    }

    public static boolean isTerminal(String status) {
        return status != null && (EXACT_TERMINAL.contains(status) || status.startsWith(FAILED_PREFIX));
    }

    /**
     * A status after which the instance ends itself, so the CLI must leave a live one alone:
     * {@code completed} and {@code failed:<n>} are written by the instance, which then uploads its
     * boot log and terminates; {@code timed-out} is written by the instance's watchdog, which does
     * the same — or by the launching CLI's poll cap, which has terminated the instance already.
     * Only {@code cancelled} is written by someone who promises nothing about the instance.
     */
    public static boolean endsItself(String status) {
        return COMPLETED.equals(status) || TIMED_OUT.equals(status)
            || (status != null && status.startsWith(FAILED_PREFIX));
    }

    /** Not yet at an outcome: the only statuses a write may move on from. */
    public static boolean isInFlight(String status) {
        return LAUNCHING.equals(status) || LAUNCHED.equals(status) || RUNNING.equals(status);
    }
}
