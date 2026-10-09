package pl.wsztajerowski.baas.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobLayoutTest {

    private static final String ID = "20260820T174432812Z-a3f9c21b";

    @Test
    void jobPrefixIsProjectMajor() {
        assertThat(JobLayout.jobPrefix("lynx-journal", ID))
            .isEqualTo("jobs/lynx-journal/" + ID);
    }

    @Test
    void inputPrefixSitsInsideTheJobPrefix() {
        assertThat(JobLayout.inputPrefix("lynx-journal", ID))
            .isEqualTo("jobs/lynx-journal/" + ID + "/input");
    }

    @Test
    void runnerJarLivesOutsideTheJobTree() {
        assertThat(JobLayout.runnerJarKey("1.4.2"))
            .isEqualTo("releases/1.4.2/benchmark-runner.jar");
    }

    @Test
    void theBenchmarkJarSitsInTheJobsOwnInput() {
        assertThat(JobLayout.benchmarkJarKey("lynx-journal", ID))
            .isEqualTo("jobs/lynx-journal/" + ID + "/input/benchmark.jar");
    }

    @Test
    void aRunnerJarOverrideStaysPerJobRatherThanUnderReleases() {
        assertThat(JobLayout.runnerJarOverrideKey("lynx-journal", ID))
            .isEqualTo("jobs/lynx-journal/" + ID + "/input/runner.jar");
        assertThat(JobLayout.runnerJarOverrideKey("lynx-journal", ID))
            .doesNotStartWith(JobLayout.RELEASES_PREFIX);
    }

    @Test
    void bothInputKeysSitUnderTheInputPrefix() {
        String input = JobLayout.inputPrefix("p", ID);
        assertThat(JobLayout.benchmarkJarKey("p", ID)).startsWith(input + "/");
        assertThat(JobLayout.runnerJarOverrideKey("p", ID)).startsWith(input + "/");
    }

    @Test
    void neitherPrefixEndsWithASlash() {
        assertThat(JobLayout.jobPrefix("p", ID)).doesNotEndWith("/");
        assertThat(JobLayout.inputPrefix("p", ID)).doesNotEndWith("/");
    }

    @Test
    void aBlankProjectIsRejected() {
        assertThatThrownBy(() -> JobLayout.jobPrefix("  ", ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("project");
    }

    @Test
    void aBlankJobIdIsRejected() {
        assertThatThrownBy(() -> JobLayout.jobPrefix("p", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("jobId");
    }

    @Test
    void aBlankVersionIsRejected() {
        assertThatThrownBy(() -> JobLayout.runnerJarKey(""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("version");
    }
}
