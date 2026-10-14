package pl.wsztajerowski.baas.commands.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SetupCommandTest {

    /**
     * {@code --workflow-id} and {@code --workflow-branch} are not reinstated — they configured the
     * GHA dispatch path, which is gone. {@code --oidc-provider-arn} is gone too: the provider's ARN
     * is fixed by the account, so setup derives it. The prefix has its own test below — it is derived from
     * the caller's AWS account and cannot be named on the command line at all.
     */
    @ParameterizedTest
    @ValueSource(strings = {"--workflow-id", "--workflow-branch", "--oidc-provider-arn"})
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

    /**
     * A deployment is named only by the global {@code --deployment}. Setup has no option of its own
     * for it, so no second spelling can disagree with the one every other command reads.
     */
    @ParameterizedTest
    @ValueSource(strings = {"--mode", "--prefix", "--name"})
    void setupHasNoNamingOptionOfItsOwn(String rejected) {
        CommandLine cmd = new CommandLine(new SetupCommand());

        assertThatThrownBy(() -> cmd.parseArgs(rejected, "dev"))
            .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
    }

    // ─── Existing-VPC support is removed ─────────────────────────────────────────

    /** Every deployment uses the VPC its own stack creates (setup-prerequisites). */
    @ParameterizedTest
    @ValueSource(strings = {"--use-existing-vpc", "--vpc-id", "--subnet-id", "--sg-id"})
    void theExistingVpcOptionsAreGone(String removed) {
        CommandLine cmd = new CommandLine(new SetupCommand());

        assertThatThrownBy(() -> cmd.parseArgs(removed, "x"))
            .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
    }

    @Test
    void rejectsTheRemovedMongoUriOption() {
        CommandLine cmd = new CommandLine(new SetupCommand());

        assertThatThrownBy(() -> cmd.parseArgs("--mongo-uri", "mongodb+srv://user:pass@host/db"))
            .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
    }

    /** Every prompt has an option; these two are setup's own, and setup has no --yes. */
    @Test
    void theWalkthroughsOwnOptionsParseAndThereIsNoYes() {
        assertThatCode(() -> new CommandLine(new SetupCommand()).parseArgs("--non-interactive", "--build-image"))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> new CommandLine(new SetupCommand()).parseArgs("--yes"))
            .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
    }
}
