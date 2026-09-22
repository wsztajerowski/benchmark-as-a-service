package pl.wsztajerowski.baas.commands.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SetupCommandTest {

    /**
     * {@code --workflow-id} and {@code --workflow-branch} are not reinstated — they configured the
     * GHA dispatch path, which is gone. The prefix has its own test below — it is derived from
     * the caller's AWS account and cannot be named on the command line at all.
     */
    @ParameterizedTest
    @ValueSource(strings = {"--workflow-id", "--workflow-branch"})
    void rejectsRemovedGitHubOidcOptions(String removedOption) {
        CommandLine cmd = new CommandLine(new SetupCommand());

        assertThatThrownBy(() -> cmd.parseArgs(removedOption, "some-value"))
            .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
    }

    // ─── GitHub OIDC federation ──────────────────────────────────────────────────

    private static SetupCommand parsed(String... args) {
        var command = new SetupCommand();
        new CommandLine(command).parseArgs(args);
        return command;
    }

    /**
     * Deleting WorkflowRole moves the federated trust onto BaasCliOperatorRole, which the core
     * stack owns — so setting and revoking it is something `baas admin setup` has to be able to
     * express. This reverses the requirement the three options used to be rejected under.
     */
    @Test
    void acceptsTheFederationOptionsAgain() {
        var command = parsed("--oidc-provider-arn", "arn:aws:iam::1:oidc-provider/x",
            "--github-org", "wsztajerowski", "--github-repo", "benchmark-as-a-service");

        assertThat(command.federationParameters())
            .containsEntry("GitHubOidcProviderArn", "arn:aws:iam::1:oidc-provider/x")
            .containsEntry("GitHubOrg", "wsztajerowski")
            .containsEntry("GitHubRepo", "repo:wsztajerowski/benchmark-as-a-service:*");
    }

    /**
     * One installation serving two repositories is otherwise a template migration. The patterns
     * are composed here because CloudFormation cannot iterate a list.
     */
    @Test
    void twoRepositoriesBecomeTwoSubjectPatterns() {
        assertThat(parsed("--oidc-provider-arn", "arn:x", "--github-org", "acme",
            "--github-repo", "one", "--github-repo", "two").federationParameters())
            .containsEntry("GitHubRepo", "repo:acme/one:*,repo:acme/two:*");

        assertThat(parsed("--oidc-provider-arn", "arn:x", "--github-org", "acme",
            "--github-repo", "one,two").federationParameters())
            .as("comma-separated must behave identically to a repeated option")
            .containsEntry("GitHubRepo", "repo:acme/one:*,repo:acme/two:*");
    }

    /**
     * The whole point of carrying values forward: a setup run for an unrelated reason — a new
     * runner image, a networking change — must not touch the federated trust. An empty map is
     * what tells {@code updateStackParameters} to leave those parameters alone.
     */
    @Test
    void anUnrelatedSetupNamesNoFederationParameterAtAll() {
        assertThat(parsed("--region", "eu-central-1").federationParameters())
            .as("resubmitting Default: \"\" here is what silently revoked CI's access")
            .isEmpty();
    }

    /** Revocation is the one gesture that removes the trust, and it says so explicitly. */
    /**
     * The create path cannot use {@code UsePreviousValue} — CloudFormation rejects it for a
     * parameter with no previous value — so it must send explicit values. It used to send all
     * three <em>empty</em> unconditionally, which silently discarded the federation options the
     * invocation named: `baas admin setup --github-org ... --oidc-provider-arn ...` reported
     * success against a fresh stack and deployed a role CI could not assume. Found by pointing CI
     * at a freshly created installation.
     */
    @Test
    void aCreateCarriesTheFederationOptionsItWasGiven() {
        var command = parsed("--oidc-provider-arn", "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com",
            "--github-org", "acme", "--github-repo", "widgets");

        assertThat(command.federationParametersForCreate()).containsExactlyInAnyOrderEntriesOf(Map.of(
            "GitHubOidcProviderArn", "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com",
            "GitHubOrg", "acme",
            "GitHubRepo", "repo:acme/widgets:*"));
    }

    @Test
    void aCreateNamingNoFederationSubmitsAllThreeEmpty() {
        assertThat(parsed().federationParametersForCreate()).containsExactlyInAnyOrderEntriesOf(Map.of(
            "GitHubOidcProviderArn", "", "GitHubOrg", "", "GitHubRepo", ""));
    }

    @Test
    void revocationSubmitsAllThreeParametersEmpty() {
        assertThat(parsed("--revoke-github-oidc").federationParameters())
            .containsEntry("GitHubOidcProviderArn", "")
            .containsEntry("GitHubOrg", "")
            .containsEntry("GitHubRepo", "");
    }

    @Test
    void revokingAndSettingAtOnceIsAUsageError() {
        var command = parsed("--revoke-github-oidc", "--github-org", "acme");

        assertThatThrownBy(command::validateFederationOptions)
            .isInstanceOf(CommandLine.ParameterException.class)
            .hasMessageContaining("cannot be combined")
            .hasMessageContaining("Nothing was deployed");
    }

    /**
     * A partial set makes the template's condition evaluate false, so the deploy succeeds and CI
     * silently has no access — the exact failure shape this change exists to remove, arriving
     * from the other direction.
     */
    @Test
    void namingSomeFederationOptionsButNotAllIsAUsageError() {
        assertThatThrownBy(parsed("--github-org", "acme")::validateFederationOptions)
            .isInstanceOf(CommandLine.ParameterException.class)
            .hasMessageContaining("--oidc-provider-arn")
            .hasMessageContaining("--github-repo");
    }

    @Test
    void aPlainSetupPassesValidation() {
        assertThatCode(parsed("--region", "eu-central-1")::validateFederationOptions)
            .doesNotThrowAnyException();
        assertThatCode(parsed("--revoke-github-oidc")::validateFederationOptions)
            .doesNotThrowAnyException();
    }

    /**
     * The deployed stack's parameters are the single source of truth. A second copy in
     * ~/.baas/config.yaml would drift, and would make "whose laptop ran setup last" part of
     * whether continuous integration keeps working.
     */
    @Test
    void theConfigFileKeepsNoCopyOfTheFederationValues() {
        var configFields = java.util.stream.Stream.of(
                pl.wsztajerowski.baas.config.BaasConfig.class,
                pl.wsztajerowski.baas.config.BaasConfig.AwsConfig.class)
            .flatMap(type -> java.util.Arrays.stream(type.getDeclaredFields()))
            .map(java.lang.reflect.Field::getName)
            .map(name -> name.toLowerCase(java.util.Locale.ROOT))
            .toList();

        assertThat(configFields)
            .noneMatch(name -> name.contains("github"))
            .noneMatch(name -> name.contains("oidc"));
    }

    // ─── Installation naming ─────────────────────────────────────────────────────

    @Test
    void theInstallationIsNamedAfterTheAccountAlone() {
        assertThat(SetupCommand.computePrefix("123456789012")).isEqualTo("baas-123456789012");
    }

    /**
     * The point of the change: the identity holding the credentials must not reach the name. An
     * IAM user, an SSO session and a role-chained session on one account all address the same
     * installation.
     */
    @Test
    void theCallerIdentityNeverReachesThePrefix() {
        assertThat(SetupCommand.computePrefix("123456789012"))
            .isEqualTo(SetupCommand.computePrefix("123456789012"));
    }

    @Test
    void differentAccountsAreDifferentInstallations() {
        assertThat(SetupCommand.computePrefix("123456789012"))
            .isNotEqualTo(SetupCommand.computePrefix("210987654321"));
    }

    @Test
    void anAccountThatIsNotTwelveDigitsIsRejected() {
        assertThatThrownBy(() -> SetupCommand.computePrefix("not-an-account"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not-an-account");
    }

    /**
     * There is exactly one installation per account and the CLI cannot be told otherwise. A second
     * installation — the one a BaaS developer wants for scratch work — is created by deploying the
     * core template by hand with a different {@code ResourceNamePrefix}, and adopted with
     * {@code baas config sync --name}. See infra/README.md. Keeping that out of the CLI is what
     * stops "which installation am I on?" becoming a question a user of BaaS ever has to ask.
     */
    @ParameterizedTest
    @ValueSource(strings = {"--mode", "--prefix", "--name", "--installation"})
    void theInstallationCannotBeSelectedOnTheCommandLine(String rejected) {
        CommandLine cmd = new CommandLine(new SetupCommand());

        assertThatThrownBy(() -> cmd.parseArgs(rejected, "dev"))
            .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
    }

    // ─── Networking is immutable once the installation exists ───────────────────

    @Test
    void namingNoNetworkingOptionsSubmitsNoNetworkingParameters() {
        assertThat(parsed().networkingParameters()).isEmpty();
    }

    @Test
    void namingNetworkingOptionsSubmitsAllFour() {
        var submitted = parsed("--use-existing-vpc", "--vpc-id", "vpc-123",
            "--subnet-id", "subnet-123", "--sg-id", "sg-123").networkingParameters();

        assertThat(submitted).containsExactlyInAnyOrderEntriesOf(Map.of(
            "UseExistingVpc", "true",
            "ExistingVpcId", "vpc-123",
            "ExistingSubnetId", "subnet-123",
            "ExistingSecurityGroupId", "sg-123"));
    }

    /**
     * The failure this closes: {@code SetupCommand} used to send these four unconditionally, so a
     * teammate's plain {@code baas admin setup} against a shared installation deployed with
     * {@code --use-existing-vpc} submitted {@code UseExistingVpc=false} and rebuilt the networking
     * underneath everyone.
     */
    @Test
    void anUpdateThatWouldChangeNetworkingIsRefused() {
        Map<String, String> deployed = Map.of(
            "UseExistingVpc", "true",
            "ExistingVpcId", "vpc-123",
            "ExistingSubnetId", "subnet-123",
            "ExistingSecurityGroupId", "sg-123");
        Map<String, String> submitted = Map.of(
            "UseExistingVpc", "true",
            "ExistingVpcId", "vpc-999",
            "ExistingSubnetId", "subnet-123",
            "ExistingSecurityGroupId", "sg-123");

        assertThatThrownBy(() -> SetupCommand.requireNetworkingUnchanged(submitted, deployed))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ExistingVpcId")
            .hasMessageContaining("vpc-123")
            .hasMessageContaining("vpc-999");
    }

    @Test
    void anUpdateRepeatingTheDeployedNetworkingProceeds() {
        Map<String, String> deployed = Map.of("UseExistingVpc", "true", "ExistingVpcId", "vpc-123");

        assertThatCode(() -> SetupCommand.requireNetworkingUnchanged(deployed, deployed))
            .doesNotThrowAnyException();
    }

    @Test
    void anUpdateNamingNoNetworkingProceedsWhateverIsDeployed() {
        Map<String, String> deployed = Map.of("UseExistingVpc", "true", "ExistingVpcId", "vpc-123");

        assertThatCode(() -> SetupCommand.requireNetworkingUnchanged(Map.of(), deployed))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsTheRemovedMongoUriOption() {
        CommandLine cmd = new CommandLine(new SetupCommand());

        assertThatThrownBy(() -> cmd.parseArgs("--mongo-uri", "mongodb+srv://user:pass@host/db"))
            .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
    }
}
