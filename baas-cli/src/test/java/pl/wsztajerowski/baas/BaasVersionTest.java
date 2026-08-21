package pl.wsztajerowski.baas;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BaasVersionTest {

    @Test
    void placeholderIsNotAReleasedVersion() {
        assertThat(BaasVersion.isReleased(BaasVersion.PLACEHOLDER)).isFalse();
    }

    @Test
    void absentManifestEntryReadsAsThePlaceholder() {
        assertThat(BaasVersion.isReleased(null)).isFalse();
        assertThat(BaasVersion.isReleased("  ")).isFalse();
    }

    @Test
    void aRealVersionIsReleased() {
        assertThat(BaasVersion.isReleased("1.4.2")).isTrue();
    }

    @Test
    void currentNeverReturnsNull() {
        assertThat(BaasVersion.current()).isNotNull();
    }
}
