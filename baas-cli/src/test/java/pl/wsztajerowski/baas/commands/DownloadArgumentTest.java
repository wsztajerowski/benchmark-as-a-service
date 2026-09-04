package pl.wsztajerowski.baas.commands;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
