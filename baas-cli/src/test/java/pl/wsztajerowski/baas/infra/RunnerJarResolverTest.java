package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunnerJarResolverTest {

    private static final byte[] PAYLOAD = "pretend-jar".getBytes(StandardCharsets.UTF_8);

    @Test
    void aMismatchIsAHardFailure() {
        assertThatThrownBy(() -> RunnerJarResolver.verify(PAYLOAD, "0".repeat(64)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("checksum")
            .hasMessageContaining("Nothing was uploaded");
    }

    @Test
    void aBlankChecksumIsAFailureNotASkip() {
        assertThatThrownBy(() -> RunnerJarResolver.verify(PAYLOAD, "  "))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("checksum");
    }

    @Test
    void aMatchingChecksumPasses() {
        assertThatCode(() -> RunnerJarResolver.verify(PAYLOAD, RunnerJarResolver.sha256Hex(PAYLOAD)))
            .doesNotThrowAnyException();
    }

    /** sha256sum's own output is lowercase with a trailing newline; other tools shout. */
    @Test
    void theChecksumComparisonIgnoresCaseAndSurroundingWhitespace() {
        String upper = RunnerJarResolver.sha256Hex(PAYLOAD).toUpperCase();

        assertThatCode(() -> RunnerJarResolver.verify(PAYLOAD, "  " + upper + "  \n"))
            .doesNotThrowAnyException();
    }

    /** A `sha256sum <file>` line is "<hex>  <name>"; only the first field is the digest. */
    @Test
    void theChecksumComparisonAcceptsASha256sumLine() {
        String line = RunnerJarResolver.sha256Hex(PAYLOAD) + "  benchmark-runner.jar\n";

        assertThatCode(() -> RunnerJarResolver.verify(PAYLOAD, line)).doesNotThrowAnyException();
    }

    @Test
    void sha256HexIsLowercaseAndFixedWidth() {
        assertThat(RunnerJarResolver.sha256Hex(PAYLOAD))
            .hasSize(64)
            .matches("[0-9a-f]{64}");
    }

    @Test
    void theAssetUrlNamesTheConfiguredRepositoryAndVersion() {
        assertThat(RunnerJarResolver.assetUrl("acme/baas", "1.4.2", "benchmark-runner.jar"))
            .isEqualTo("https://github.com/acme/baas/releases/download/v1.4.2/benchmark-runner.jar");
    }

    @Test
    void aBlankSourceRepositoryIsRejectedRatherThanDefaulted() {
        assertThatThrownBy(() -> RunnerJarResolver.assetUrl("  ", "1.4.2", "benchmark-runner.jar"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("repository");
    }
}
