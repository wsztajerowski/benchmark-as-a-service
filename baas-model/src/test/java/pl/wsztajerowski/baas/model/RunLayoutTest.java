package pl.wsztajerowski.baas.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunLayoutTest {

    private static final String ID = "20260820T174432812Z-a3f9c21b";

    @Test
    void runPrefixIsProjectMajor() {
        assertThat(RunLayout.runPrefix("lynx-journal", ID))
            .isEqualTo("runs/lynx-journal/" + ID);
    }

    @Test
    void inputPrefixSitsInsideTheRunPrefix() {
        assertThat(RunLayout.inputPrefix("lynx-journal", ID))
            .isEqualTo("runs/lynx-journal/" + ID + "/input");
    }

    @Test
    void runnerJarLivesOutsideTheRunTree() {
        assertThat(RunLayout.runnerJarKey("1.4.2"))
            .isEqualTo("releases/1.4.2/benchmark-runner.jar");
    }

    @Test
    void neitherPrefixEndsWithASlash() {
        assertThat(RunLayout.runPrefix("p", ID)).doesNotEndWith("/");
        assertThat(RunLayout.inputPrefix("p", ID)).doesNotEndWith("/");
    }

    @Test
    void aBlankProjectIsRejected() {
        assertThatThrownBy(() -> RunLayout.runPrefix("  ", ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("project");
    }

    @Test
    void aBlankRunIdIsRejected() {
        assertThatThrownBy(() -> RunLayout.runPrefix("p", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("runId");
    }

    @Test
    void aBlankVersionIsRejected() {
        assertThatThrownBy(() -> RunLayout.runnerJarKey(""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("version");
    }
}
