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
    void defaultsToTheAccountsOwnDeployment() {
        assertThat(parsed("--for-account", "123456789012").renderedPrefix("123456789012"))
            .isEqualTo("baas-123456789012");
    }

    /**
     * A by-hand development deployment needs its own rendered policy, and nothing else in the
     * CLI knows such a deployment exists. This prints; it grants nothing.
     */
    @Test
    void anExplicitPrefixRendersForAByHandDeployment() {
        assertThat(parsed("--prefix", "baas-123456789012-dev").renderedPrefix("123456789012"))
            .isEqualTo("baas-123456789012-dev");
    }

    /** U19: the region is baked into the policy, and this command runs before any config exists. */
    @Test
    void anExplicitRegionWinsOverTheConfiguredOne() {
        var config = new pl.wsztajerowski.baas.config.BaasConfig();
        config.getAws().setRegion("eu-central-1");

        assertThat(parsed("--region", "us-west-2").renderedRegion(config)).isEqualTo("us-west-2");
        assertThat(parsed().renderedRegion(config)).isEqualTo("eu-central-1");
    }

    /** The region reaches the ARNs and the aws:RequestedRegion conditions, not just the log line. */
    @Test
    void theRenderedPolicyNamesTheChosenRegion() {
        String policy = new pl.wsztajerowski.baas.infra.DeployerPolicyRenderer()
            .render("123456789012", parsed("--region", "us-west-2")
                .renderedRegion(new pl.wsztajerowski.baas.config.BaasConfig()), "baas-123456789012");

        assertThat(policy)
            .contains("arn:aws:dynamodb:us-west-2:123456789012:table/baas-123456789012-results")
            .contains("\"aws:RequestedRegion\": \"us-west-2\"")
            .doesNotContain("eu-central-1");
    }
}
