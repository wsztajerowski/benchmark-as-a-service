package pl.wsztajerowski.baas.commands.admin;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import pl.wsztajerowski.baas.BaasApp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The deployer profile is set in one place: `admin deployment setup` takes it and saves it to
 * aws.deployerProfile, and every other admin command reads it from the configuration
 * (`config set --deployer-aws-profile` changes it). `image build` and `image show` used to take a
 * per-invocation override that teardown never had; nothing depended on it.
 */
class AdminProfileOptionTest {

    @Test
    void onlySetupTakesTheDeployerProfile() {
        var setup = new CommandLine(new BaasApp())
            .parseArgs("admin", "deployment", "setup", "--deployer-aws-profile", "deployer");
        assertThat(setup.subcommand().subcommand().subcommand().hasMatchedOption("--deployer-aws-profile")).isTrue();

        for (String command : new String[]{"image build", "image show", "deployment teardown"}) {
            assertThatThrownBy(() -> new CommandLine(new BaasApp())
                .parseArgs(("admin " + command + " --deployer-aws-profile deployer").split(" ")))
                .as(command)
                .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
        }
    }

    /** Named for the deployer, beside `--operator-aws-profile`; the old spellings are gone everywhere. */
    @Test
    void theOldOptionsAreGone() {
        for (String line : new String[]{"admin deployment setup --aws-profile", "config set --aws-profile",
                "admin deployment setup --deployer-profile", "config set --deployer-profile",
                "config set --operator-profile"}) {
            assertThatThrownBy(() -> new CommandLine(new BaasApp())
                .parseArgs((line + " deployer").split(" ")))
                .as(line)
                .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
        }
    }
}
