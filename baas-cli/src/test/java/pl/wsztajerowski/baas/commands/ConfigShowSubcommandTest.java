package pl.wsztajerowski.baas.commands;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.config.BaasConfig;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigShowSubcommandTest {

    /** `config show` is where an operator checks whether git derivation is on, and which file is read. */
    @Test
    void labelsTheDeployerAndTheOperatorProfile() {
        BaasConfig config = new BaasConfig();
        config.getAws().setDeployerProfile("baas-admin");
        config.getAws().setOperatorProfile("baas-operator");

        String shown = ConfigShowSubcommand.render(config, Path.of("c.yaml"));

        assertThat(shown)
            .contains("deployerProfile:          baas-admin")
            .contains("operatorProfile:          baas-operator")
            .doesNotContain("  profile:");
    }

    @Test
    void reportsTheFileAndTheGitAndWatchdogPreferences() {
        BaasConfig config = new BaasConfig();
        config.getGit().setResolveProject(true);
        config.getEc2().setWatchdogMarginSeconds(120);

        String shown = ConfigShowSubcommand.render(config, Path.of("c.yaml"));

        assertThat(shown)
            .contains("Config file: c.yaml")
            .contains("resolveProject:           true")
            .contains("watchdogMarginSeconds:    120")
            .doesNotContain("wallClockHardKillSeconds");
    }
}
