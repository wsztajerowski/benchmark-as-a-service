package pl.wsztajerowski.baas.jobs;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * When a live job's watchdog will terminate its instance: the launch time plus the bound the
 * reservation recorded. The watchdog starts its sleep only once the instance has booted, so the real
 * deadline is up to about a minute later than this — never earlier, so the time shown is never time
 * that is not there.
 */
final class WatchdogDeadline {

    private static final DateTimeFormatter CLOCK =
        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);

    private WatchdogDeadline() {}

    /** Absent for a job reserved before the bound was recorded, or an instance with no launch time. */
    static Optional<Instant> of(Instant launchTime, Integer watchdogSeconds) {
        if (launchTime == null || watchdogSeconds == null) {
            return Optional.empty();
        }
        return Optional.of(launchTime.plusSeconds(watchdogSeconds));
    }

    /** {@code about 14:32 UTC (in 1 h 52 min)}. */
    static String describe(Instant deadline, Instant now) {
        long minutes = Math.max(0, Duration.between(now, deadline).toMinutes());
        String in = minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min";
        return "about " + CLOCK.format(deadline) + " UTC (in " + in + ")";
    }
}
