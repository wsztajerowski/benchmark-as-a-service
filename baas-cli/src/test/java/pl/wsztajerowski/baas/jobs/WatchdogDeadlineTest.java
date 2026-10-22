package pl.wsztajerowski.baas.jobs;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WatchdogDeadlineTest {

    private static final Instant LAUNCHED = Instant.parse("2026-10-22T12:40:00Z");

    @Test
    void theDeadlineIsTheLaunchTimePlusTheBound() {
        assertThat(WatchdogDeadline.of(LAUNCHED, 7500)).contains(Instant.parse("2026-10-22T14:45:00Z"));
    }

    @Test
    void noBoundOrNoLaunchTimeMeansNoDeadline() {
        assertThat(WatchdogDeadline.of(LAUNCHED, null)).isEmpty();
        assertThat(WatchdogDeadline.of(null, 900)).isEmpty();
    }

    @Test
    void itIsDescribedAsAClockTimeAndWhatIsLeft() {
        var deadline = Instant.parse("2026-10-22T14:45:00Z");

        assertThat(WatchdogDeadline.describe(deadline, Instant.parse("2026-10-22T12:53:00Z")))
            .isEqualTo("about 14:45 UTC (in 1 h 52 min)");
        assertThat(WatchdogDeadline.describe(deadline, Instant.parse("2026-10-22T14:30:00Z")))
            .isEqualTo("about 14:45 UTC (in 15 min)");
    }

    @Test
    void aPassedDeadlineIsNeverNegative() {
        var deadline = Instant.parse("2026-10-22T14:45:00Z");

        assertThat(WatchdogDeadline.describe(deadline, deadline.plusSeconds(90))).endsWith("(in 0 min)");
    }
}
