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
}
