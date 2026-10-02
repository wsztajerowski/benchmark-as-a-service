package pl.wsztajerowski.baas.commands.admin;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import pl.wsztajerowski.baas.BaasApp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The deployer profile is set in one place: `admin setup` takes it and saves it to aws.profile, and
 * every other admin command reads it from the configuration (`config set --aws-profile` changes it).
 * build-image and image used to take a per-invocation override that teardown and deployer-policy
 * never had; nothing depended on it.
 */
class AdminProfileOptionTest {

    @Test
    void onlySetupTakesTheDeployerProfile() {
        var setup = new CommandLine(new BaasApp()).parseArgs("admin", "setup", "--aws-profile", "deployer");
        assertThat(setup.subcommand().subcommand().hasMatchedOption("--aws-profile")).isTrue();

        for (String command : new String[]{"build-image", "image", "teardown", "deployer-policy"}) {
            assertThatThrownBy(() -> new CommandLine(new BaasApp())
                .parseArgs("admin", command, "--aws-profile", "deployer"))
                .as(command)
                .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
        }
    }
}
