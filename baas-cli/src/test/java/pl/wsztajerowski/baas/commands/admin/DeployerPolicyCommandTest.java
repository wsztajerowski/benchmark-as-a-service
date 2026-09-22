package pl.wsztajerowski.baas.commands.admin;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeployerPolicyCommandTest {

    private static DeployerPolicyCommand parsed(String... args) {
        var command = new DeployerPolicyCommand();
        new CommandLine(command).parseArgs(args);
        return command;
    }

    /**
     * The rendered policy no longer depends on who is asking — every resource it names derives from
     * the account, the region and the mode. Rendering "for someone else" is therefore rendering for
     * another <em>account</em>, and an ARN is both the wrong shape and a value only the target user
     * can print for themselves under SSO.
     */
    @Test
    void rendersForAnotherAccount() {
        assertThat(parsed("--for-account", "210987654321").forAccount).isEqualTo("210987654321");
    }

    @Test
    void theArnOptionIsGone() {
        CommandLine cmd = new CommandLine(new DeployerPolicyCommand());

        assertThatThrownBy(() -> cmd.parseArgs("--for-arn", "arn:aws:iam::123456789012:user/alice"))
            .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
    }

    @Test
    void anAccountThatIsNotTwelveDigitsIsRejected() {
        assertThatThrownBy(() -> parsed("--for-account", "arn:aws:iam::123456789012:user/alice").resolveAccount(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("12-digit");
    }

    @Test
    void defaultsToTheAccountsOwnInstallation() {
        assertThat(parsed("--for-account", "123456789012").renderedPrefix("123456789012"))
            .isEqualTo("baas-123456789012");
    }

    /**
     * A by-hand development installation needs its own rendered policy, and nothing else in the
     * CLI knows such an installation exists. This prints; it grants nothing.
     */
    @Test
    void anExplicitPrefixRendersForAByHandInstallation() {
        assertThat(parsed("--prefix", "baas-123456789012-dev").renderedPrefix("123456789012"))
            .isEqualTo("baas-123456789012-dev");
    }
}
