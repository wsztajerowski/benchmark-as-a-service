package pl.wsztajerowski.baas.commands;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

class DownloadArgumentTest {

    @Test
    void aRunIdentifierIsRecognised() {
        assertThat(RunReference.looksLikeRunId("20260820T174432812Z-a3f9c21b")).isTrue();
    }

    /** The branch that keeps every run stored before the unified layout retrievable. */
    @Test
    void anOldLayoutPathIsNotARunIdentifier() {
        assertThat(RunReference.looksLikeRunId("main/jmh/20260819_090000")).isFalse();
    }

    @Test
    void aNewLayoutPathIsNotARunIdentifier() {
        assertThat(RunReference.looksLikeRunId("runs/lynx-journal/20260820T174432812Z-a3f9c21b"))
            .isFalse();
    }

    @Test
    void aLegacyRequestIdIsNotMistakenForOne() {
        assertThat(RunReference.looksLikeRunId("jmh-20260819_090000")).isFalse();
    }

    @Test
    void nothingIsNotARunIdentifier() {
        assertThat(RunReference.looksLikeRunId(null)).isFalse();
        assertThat(RunReference.looksLikeRunId("")).isFalse();
    }

    @Test
    void aLiteralPathNeedsNoResultsTable() {
        assertThat(RunReference.looksLikeRunId("main/jmh/20260819_090000"))
            .as("the literal-path branch never reaches the table lookup")
            .isFalse();
    }

    // ─── Reading another installation's results ─────────────────────────────────

    /**
     * The per-command table and bucket overrides gave way to the inherited --config-path, which
     * names a whole installation. No command — read or write — declares either any more, so none can
     * be aimed at one installation's table while its configuration names another.
     */
    @Test
    void noCommandDeclaresATableOrBucketOverride() {
        for (Object command : new Object[]{
            new DownloadCommand(), new ResultsCommand(), new RunCommand(), new ConfigSetSubcommand()}) {
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
