package pl.wsztajerowski.baas.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeployerSessionTest {

    private final List<String> warnings = new ArrayList<>();
    private final List<String> lookups = new ArrayList<>();

    @BeforeEach
    void forget() {
        DeployerSession.forgetChecks();
    }

    private static BaasConfig config(String name) {
        var config = new BaasConfig();
        config.setPrefix(name);
        config.getAws().setRegion("eu-central-1");
        return config;
    }

    private void factory(String deployment, String running, String recorded) {
        DeployerSession.factory(config(deployment), new CliVersionCheck(running, "o/r", warnings::add),
            (factory, stack) -> {
                lookups.add(stack);
                return Optional.of(recorded);
            });
    }

    @Test
    void anOlderCliIsRefusedBeforeTheFactoryIsReturned() {
        assertThatThrownBy(() -> factory("dep", "8.1.0", "8.2.0"))
            .isInstanceOf(CliVersionCheck.Refused.class)
            .hasMessageContaining("baas 8.2.0");
        assertThat(lookups).containsExactly("dep");
    }

    @Test
    void aNewerAdminIsHintedOncePerProcess() {
        factory("dep", "8.2.0", "8.1.0");
        factory("dep", "8.2.0", "8.1.0");

        assertThat(lookups).containsExactly("dep");
        assertThat(warnings).singleElement().asString().contains("baas --deployment dep admin deployment setup");
    }

    @Test
    void theSameReleaseProceeds() {
        factory("dep", "8.2.0", "8.2.0");
        assertThat(warnings).isEmpty();
    }
}
