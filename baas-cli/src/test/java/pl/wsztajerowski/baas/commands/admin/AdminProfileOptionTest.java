package pl.wsztajerowski.baas.commands.admin;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import pl.wsztajerowski.baas.BaasApp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The deployer profile is set in one place: `admin deployment setup` takes it and saves it to
 * aws.deployerProfile, and every other admin command reads it from the configuration
 * (`config set --deployer-profile` changes it). `image build` and `image show` used to take a
 * per-invocation override that teardown never had; nothing depended on it.
 */
class AdminProfileOptionTest {

    @Test
    void onlySetupTakesTheDeployerProfile() {
        var setup = new CommandLine(new BaasApp())
            .parseArgs("admin", "deployment", "setup", "--deployer-profile", "deployer");
        assertThat(setup.subcommand().subcommand().subcommand().hasMatchedOption("--deployer-profile")).isTrue();

        for (String command : new String[]{"image build", "image show", "deployment teardown"}) {
            assertThatThrownBy(() -> new CommandLine(new BaasApp())
                .parseArgs(("admin " + command + " --deployer-profile deployer").split(" ")))
                .as(command)
                .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
        }
    }

    /** Named for the deployer, beside `--operator-profile`; the old spelling is gone everywhere. */
    @Test
    void theOldOptionIsGone() {
        for (String line : new String[]{"admin deployment setup", "config set"}) {
            assertThatThrownBy(() -> new CommandLine(new BaasApp())
                .parseArgs((line + " --aws-profile deployer").split(" ")))
                .as(line)
                .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
        }
    }
}
