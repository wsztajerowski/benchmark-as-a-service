package pl.wsztajerowski.baas.config;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.session.BaasConfig;

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

    /** Defaults are not in the file, and show says which values are defaults; no copied values remain. */
    @Test
    void marksDefaultsAndShowsNoCopiedValues() {
        BaasConfig config = new BaasConfig();
        config.setPrefix("wiktor-dev");
        config.getAws().setDeployerProfile("baas-admin");
        config.getEc2().setBenchmarkTimeoutSeconds(10800);

        String shown = ConfigShowSubcommand.render(config, Path.of("wiktor-dev.yaml"));

        assertThat(shown)
            .contains("benchmarkTimeoutSeconds:  10800\n")
            .contains("watchdogMarginSeconds:    300  (default)")
            .contains("the deployer profile is the source")
            .contains("assumes wiktor-dev-role-operator")
            .doesNotContain("sourceRepo")
            .doesNotContain("prefix:");
    }
}
