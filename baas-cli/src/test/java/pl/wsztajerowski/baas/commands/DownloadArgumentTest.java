package pl.wsztajerowski.baas.commands;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DownloadArgumentTest {

    @Test
    void aRunIdentifierIsRecognised() {
        assertThat(DownloadCommand.looksLikeRunId("20260820T174432812Z-a3f9c21b")).isTrue();
    }

    /** The branch that keeps every run stored before the unified layout retrievable. */
    @Test
    void anOldLayoutPathIsNotARunIdentifier() {
        assertThat(DownloadCommand.looksLikeRunId("main/jmh/20260819_090000")).isFalse();
    }

    @Test
    void aNewLayoutPathIsNotARunIdentifier() {
        assertThat(DownloadCommand.looksLikeRunId("runs/lynx-journal/20260820T174432812Z-a3f9c21b"))
            .isFalse();
    }

    @Test
    void aLegacyRequestIdIsNotMistakenForOne() {
        assertThat(DownloadCommand.looksLikeRunId("jmh-20260819_090000")).isFalse();
    }

    @Test
    void nothingIsNotARunIdentifier() {
        assertThat(DownloadCommand.looksLikeRunId(null)).isFalse();
        assertThat(DownloadCommand.looksLikeRunId("")).isFalse();
    }

    /**
     * The run-id branch needs the results table to resolve a path; the literal-path branch does
     * not. Without this guard an unsynced config turned `baas download <runId>` into a raw SDK
     * validation error, while the sibling bucket check one block above reported its own absence
     * with the command that fixes it.
     */
    @Test
    void anUnresolvableResultsTableIsReportedRatherThanPassedToTheSdk() {
        var command = new DownloadCommand();
        command.resultPath = "20260820T174432812Z-a3f9c21b";

        assertThat(command.tableUnresolvable(null)).isTrue();
        assertThat(command.tableUnresolvable("   ")).isTrue();
        assertThat(command.tableUnresolvable("baas-a1b2c3d4-results")).isFalse();
    }

    @Test
    void aLiteralPathNeedsNoResultsTable() {
        assertThat(DownloadCommand.looksLikeRunId("main/jmh/20260819_090000"))
            .as("the literal-path branch never reaches the table lookup")
            .isFalse();
    }

    // ─── Reading another installation's results ─────────────────────────────────

    /**
     * Retiring an installation leaves its bucket and results table behind as a read-only archive.
     * Without an override they would be write-once, read-never: `config set` has no such option,
     * and `config sync` cannot help once the old stack is deleted, because it reads stack outputs.
     */
    @Test
    void downloadAcceptsAResultsTableOverride() {
        var command = new DownloadCommand();
        new CommandLine(command).parseArgs("--results-table", "baas-3q7i7s65-results", "some-run-id");

        assertThat(command.resultsTableOverride).isEqualTo("baas-3q7i7s65-results");
    }

    @Test
    void downloadAcceptsABucketOverride() {
        var command = new DownloadCommand();
        new CommandLine(command).parseArgs("--bucket", "baas-3q7i7s65", "some-run-id");

        assertThat(command.bucketOverride).isEqualTo("baas-3q7i7s65");
    }

    @Test
    void resultsAcceptsAResultsTableOverride() {
        var command = new ResultsCommand();
        new CommandLine(command).parseArgs("--results-table", "baas-3q7i7s65-results");

        assertThat(command.resultsTableOverride).isEqualTo("baas-3q7i7s65-results");
    }

    /**
     * Deliberately absent from every path that writes. Persisting or honouring it there would let
     * an operator leave a machine pointed at an archive and quietly record new runs into it.
     */
    @Test
    void noWritePathDeclaresAResultsTableOverride() {
        // Asserted on the option spec rather than by parsing: `baas run` has its own required
        // options, so picocli would report one of those first and the test would pass for the
        // wrong reason.
        assertThat(optionNames(new RunCommand())).doesNotContain("--results-table");
        assertThat(optionNames(new ConfigSetSubcommand())).doesNotContain("--results-table");
    }

    private static java.util.List<String> optionNames(Object command) {
        return new CommandLine(command).getCommandSpec().options().stream()
            .flatMap(option -> java.util.Arrays.stream(option.names()))
            .toList();
    }
}
