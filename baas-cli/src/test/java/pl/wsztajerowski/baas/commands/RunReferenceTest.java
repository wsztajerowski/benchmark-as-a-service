package pl.wsztajerowski.baas.commands;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunReferenceTest {

    private final List<String> lookedUp = new ArrayList<>();

    private String lookup(String runId) {
        lookedUp.add(runId);
        return runId.equals("20261002T080250645Z-264f5dfb")
            ? "runs/baas-lifecycle-test/20261002T080250645Z-264f5dfb" : null;
    }

    @Test
    void aRunIdResolvesToItsStoredPath() {
        assertThat(RunReference.resolve("20261002T080250645Z-264f5dfb", this::lookup))
            .isEqualTo("runs/baas-lifecycle-test/20261002T080250645Z-264f5dfb");
    }

    /** Both path shapes pass through untouched, and never reach the table. */
    @Test
    void aPathIsUsedAsGivenWithoutALookup() {
        assertThat(RunReference.resolve("runs/p/20261002T080250645Z-264f5dfb", this::lookup))
            .isEqualTo("runs/p/20261002T080250645Z-264f5dfb");
        assertThat(RunReference.resolve("main/jmh/20260819_090000", this::lookup))
            .isEqualTo("main/jmh/20260819_090000");
        assertThat(lookedUp).isEmpty();
    }

    @Test
    void aTrailingSlashIsTolerated() {
        assertThat(RunReference.resolve("runs/p/20261002T080250645Z-264f5dfb/", this::lookup))
            .isEqualTo("runs/p/20261002T080250645Z-264f5dfb");
    }

    /** Includes a run that failed before storing a measurement: it has no index entry yet. */
    @Test
    void anUnknownRunIdResolvesToNothingAndTheMessageNamesTheWayOut() {
        assertThat(RunReference.resolve("20261002T000000000Z-00000000", this::lookup)).isNull();
        assertThat(RunReference.noSuchRun("20261002T000000000Z-00000000"))
            .contains("20261002T000000000Z-00000000", "runs/<project>/<runId>");
    }
}
