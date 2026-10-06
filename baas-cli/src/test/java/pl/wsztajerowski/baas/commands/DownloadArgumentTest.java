package pl.wsztajerowski.baas.commands;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

class DownloadArgumentTest {

    @Test
    void aJobIdentifierIsRecognised() {
        assertThat(JobReference.looksLikeJobId("20260820T174432812Z-a3f9c21b")).isTrue();
    }

    /** The branch that keeps every job stored before the unified layout retrievable. */
    @Test
    void anOldLayoutPathIsNotAJobIdentifier() {
        assertThat(JobReference.looksLikeJobId("main/jmh/20260819_090000")).isFalse();
    }

    @Test
    void aNewLayoutPathIsNotAJobIdentifier() {
        assertThat(JobReference.looksLikeJobId("jobs/lynx-journal/20260820T174432812Z-a3f9c21b"))
            .isFalse();
    }

    @Test
    void aLegacyJobIdIsNotMistakenForOne() {
        assertThat(JobReference.looksLikeJobId("jmh-20260819_090000")).isFalse();
    }

    @Test
    void nothingIsNotAJobIdentifier() {
        assertThat(JobReference.looksLikeJobId(null)).isFalse();
        assertThat(JobReference.looksLikeJobId("")).isFalse();
    }

    @Test
    void aLiteralPathNeedsNoResultsTable() {
        assertThat(JobReference.looksLikeJobId("main/jmh/20260819_090000"))
            .as("the literal-path branch never reaches the table lookup")
            .isFalse();
    }

    // ─── Reading another deployment's results ─────────────────────────────────

    /**
     * The per-command table and bucket overrides gave way to the inherited --config-path, which
     * names a whole deployment. No command — read or write — declares either any more, so none can
     * be aimed at one deployment's table while its configuration names another.
     */
    @Test
    void noCommandDeclaresATableOrBucketOverride() {
        for (Object command : new Object[]{
            new JobsDownloadSubcommand(), new ResultsQuerySubcommand(), new RunCommand(), new ConfigSetSubcommand()}) {
            assertThat(optionNames(command))
                .as(command.getClass().getSimpleName())
                .doesNotContain("--results-table", "--bucket");
        }
    }

    private static java.util.List<String> optionNames(Object command) {
        return new CommandLine(command).getCommandSpec().options().stream()
            .flatMap(option -> java.util.Arrays.stream(option.names()))
            .toList();
    }
}
