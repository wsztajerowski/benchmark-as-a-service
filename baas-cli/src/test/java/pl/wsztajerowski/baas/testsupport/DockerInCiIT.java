package pl.wsztajerowski.baas.testsupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.DockerClientFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T12: every IT here is {@code @Testcontainers(disabledWithoutDocker = true)}, so without Docker they
 * all skip and the build stays green, the store contract's only real binding included. Locally that
 * is a choice; in CI it is a broken runner, so there it fails by name.
 */
@EnabledIfEnvironmentVariable(named = "CI", matches = "(?i)true|1")
class DockerInCiIT {

    @Test
    void dockerIsAvailableInCi() {
        assertThat(DockerClientFactory.instance().isDockerAvailable())
            .as("CI is set but Docker is not available: every Testcontainers IT would be skipped")
            .isTrue();
    }
}
