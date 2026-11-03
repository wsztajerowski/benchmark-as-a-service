package pl.wsztajerowski.baas.testsupport;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.profiles.ProfileFileLocation;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** T10: the listener is registered, so the SDK resolves no profile file under the developer's home. */
class NoRealAwsProfileTest {

    @Test
    void theSdkFindsNoAwsProfileFiles() {
        Path realHome = Path.of(System.getenv().getOrDefault("HOME", "/nonexistent-home"));

        assertThat(ProfileFileLocation.configurationFilePath().startsWith(realHome.resolve(".aws"))).isFalse();
        assertThat(ProfileFileLocation.credentialsFilePath().startsWith(realHome.resolve(".aws"))).isFalse();
        assertThat(ProfileFileLocation.configurationFileLocation()).isEmpty();
        assertThat(ProfileFileLocation.credentialsFileLocation()).isEmpty();
    }
}
