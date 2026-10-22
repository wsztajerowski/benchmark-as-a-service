package pl.wsztajerowski.baas.jobs;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A runner instance's EC2 state, and the one place a state name is interpreted. Every command that
 * asks "is it still there?" asks this type, so the poll, {@code jobs terminate}, the shutdown hook
 * and {@code jobs connect} cannot each grow a definition of their own.
 *
 * <p>{@link #UNKNOWN} — the instance could not be described — is neither live nor ended: a failed
 * describe must not end a poll, cut a session, or count as an instance worth terminating.
 */
public enum InstanceState {
    PENDING, RUNNING, SHUTTING_DOWN, TERMINATED, STOPPING, STOPPED, UNKNOWN;

    /** The state EC2 named, or {@link #UNKNOWN} for a name this type does not know. */
    public static InstanceState of(String ec2Name) {
        if (ec2Name == null) {
            return UNKNOWN;
        }
        try {
            return valueOf(ec2Name.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    /** Pending or running: an instance a job can still be using. */
    public boolean isLive() {
        return this == PENDING || this == RUNNING;
    }

    /**
     * Shutting down or terminated: gone for good. Stopping and stopped are not counted — a runner
     * shuts down into termination, so only a hand-made stop reaches them.
     */
    public boolean hasEnded() {
        return this == SHUTTING_DOWN || this == TERMINATED;
    }

    /** The EC2 names of the live states, for a server-side {@code instance-state-name} filter. */
    public static List<String> liveNames() {
        return Arrays.stream(values()).filter(InstanceState::isLive).map(InstanceState::label).toList();
    }

    /** EC2's own spelling, for display. */
    public String label() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
