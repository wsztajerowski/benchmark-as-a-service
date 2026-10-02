package pl.wsztajerowski.baas.runs;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.model.RunItem;
import pl.wsztajerowski.baas.model.RunStatus;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RunListingTest {

    private static RunItem run(String id, String project, String status, String instanceId, Map<String, String> tags) {
        return new RunItem(id, project, Instant.parse("2026-10-03T00:00:00Z"), "runs/" + project + "/" + id,
            "c5.2xlarge", status, instanceId, null, tags, null);
    }

    @Test
    void aStoredOutcomeIsShownAsIs() {
        var row = RunListing.resolve(run("r", "p", RunStatus.COMPLETED, "i-1", Map.of()), Map.of());

        assertThat(row.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(row.inFlight()).isFalse();
    }

    @Test
    void anInFlightStatusWithALiveInstanceIsInFlight() {
        var row = RunListing.resolve(run("r", "p", RunStatus.RUNNING, "i-1", Map.of()), Map.of("r", "i-1"));

        assertThat(row.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(row.inFlight()).isTrue();
    }

    @Test
    void anInFlightStatusWithNoLiveInstanceVanished() {
        var row = RunListing.resolve(run("r", "p", RunStatus.RUNNING, "i-1", Map.of()), Map.of());

        assertThat(row.status()).isEqualTo(RunStatus.VANISHED);
        assertThat(row.inFlight()).isFalse();
    }

    /** The CLI died before recording the instance; the tag still ties the run to it. */
    @Test
    void aLaunchingRunWithoutAnInstanceIdResolvesThroughTheTag() {
        var row = RunListing.resolve(run("r", "p", RunStatus.LAUNCHING, null, Map.of()), Map.of("r", "i-7"));

        assertThat(row.inFlight()).isTrue();
        assertThat(row.liveInstanceId()).isEqualTo("i-7");
    }

    @Test
    void inFlightKeepsOnlyRunsWithALiveInstance() {
        var live = Map.of("a", "i-1");
        var filter = RunListing.filter(null, Map.of(), true, live);

        assertThat(filter.test(run("a", "p", RunStatus.RUNNING, "i-1", Map.of()))).isTrue();
        assertThat(filter.test(run("b", "p", RunStatus.RUNNING, "i-2", Map.of()))).as("vanished").isFalse();
        assertThat(filter.test(run("c", "p", RunStatus.COMPLETED, "i-3", Map.of()))).isFalse();
    }

    @Test
    void projectAndEveryNamedTagMustMatch() {
        var filter = RunListing.filter("p", Map.of("source", "local", "branch", "main"), false, Map.of());

        assertThat(filter.test(run("a", "p", RunStatus.COMPLETED, null, Map.of("source", "local", "branch", "main")))).isTrue();
        assertThat(filter.test(run("b", "p", RunStatus.COMPLETED, null, Map.of("source", "local")))).isFalse();
        assertThat(filter.test(run("c", "q", RunStatus.COMPLETED, null, Map.of("source", "local", "branch", "main")))).isFalse();
    }

    /** exclude_from_results keeps fixture measurements out of comparisons; a run is an operation. */
    @Test
    void anExcludedRunIsListedLikeAnyOther() {
        var filter = RunListing.filter(null, Map.of(), false, Map.of());

        assertThat(filter.test(run("ci", "p", RunStatus.RUNNING, "i-1", Map.of("exclude_from_results", "true", "source", "ci"))))
            .isTrue();
    }
}
