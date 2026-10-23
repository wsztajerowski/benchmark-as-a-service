package pl.wsztajerowski.baas.session;

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

    @Test
    void versionsOrderNumericallyFieldByField() {
        assertThat(BaasVersion.compare("8.10.0", "8.9.0")).isPositive();
        assertThat(BaasVersion.compare("8.1.0", "8.1.1")).isNegative();
        assertThat(BaasVersion.compare("9.0.0", "8.99.99")).isPositive();
        assertThat(BaasVersion.compare("8.2.0", "8.2.0")).isZero();
    }

    @Test
    void onlyAReleasedNumericVersionIsComparable() {
        assertThat(BaasVersion.comparable("8.2.0")).isTrue();
        assertThat(BaasVersion.comparable(BaasVersion.PLACEHOLDER)).isFalse();
        assertThat(BaasVersion.comparable("8.0.0-rc.1")).isFalse();
        assertThat(BaasVersion.comparable(null)).isFalse();
        assertThat(BaasVersion.comparable("8.2")).isFalse();
    }
}
