package pl.wsztajerowski.baas.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A6: unknown keys used to vanish silently. They must still not fail a load — files from older
 * releases carry retired keys — but each one is now named.
 */
class UnknownConfigKeyTest {

    @TempDir
    Path tempDir;

    @Test
    void namesEachUnknownKeyByItsFullPathAndStillLoadsTheRest() throws Exception {
        Path file = tempDir.resolve("config.yaml");
        Files.writeString(file, """
            prefix: "baas-123456789012"
            aws:
              region: "eu-west-1"
              operatorProfil: "typo"
              coreStackName: "baas-main"
            ec2:
              wallClockHardKillSeconds: 7500
              retired:
                nested: true
            """);

        ConfigService.Read read = ConfigService.read(file);

        assertThat(read.unknownKeys()).containsExactly(
            "aws.operatorProfil", "aws.coreStackName", "ec2.wallClockHardKillSeconds", "ec2.retired");
        assertThat(read.config().getPrefix()).isEqualTo("baas-123456789012");
        assertThat(read.config().getAws().getRegion()).isEqualTo("eu-west-1");
    }

    @Test
    void aFileWithOnlyKnownKeysReportsNothing() throws Exception {
        Path file = tempDir.resolve("config.yaml");
        ConfigService service = ConfigService.at(file);
        BaasConfig config = new BaasConfig();
        config.setPrefix("baas-123456789012");
        service.save(config);

        assertThat(ConfigService.read(file).unknownKeys()).isEmpty();
    }
}
