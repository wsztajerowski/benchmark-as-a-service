package pl.wsztajerowski.baas.testsupport;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.session.ConfigService;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** However the tests are started, the configuration root is not the developer's (T10). */
class NoRealBaasHomeTest {

    @Test
    void theConfigurationRootIsNotUnderTheRealHome() {
        Path realHome = Path.of(System.getenv("HOME"));

        assertThat(ConfigService.defaultRoot().startsWith(realHome.resolve(".baas"))).isFalse();
        assertThat(ConfigService.defaultRoot().toString()).contains("baas-test-home");
    }
}
