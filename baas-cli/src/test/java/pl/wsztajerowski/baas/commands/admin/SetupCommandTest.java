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
     * GHA dispatch path, which is gone — and {@code --prefix} never comes back: the prefix is a
     * hash of the caller ARN, not a choice.
     */
    @ParameterizedTest
    @ValueSource(strings = {"--workflow-id", "--workflow-branch", "--prefix"})
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

    @Test
    void computePrefixIsDeterministicAndEightCharsLowerBase32() throws Exception {
        String arn = "arn:aws:iam::123456789012:user/dev-alice";

        String first = SetupCommand.computePrefix(arn);
        String second = SetupCommand.computePrefix(arn);

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(8);
        assertThat(first).isEqualTo(first.toLowerCase());
        assertThat(first).matches("[a-z2-7]{8}");
    }

    @Test
    void computePrefixDiffersForDifferentArns() throws Exception {
        String prefixA = SetupCommand.computePrefix("arn:aws:iam::123456789012:user/dev-alice");
        String prefixB = SetupCommand.computePrefix("arn:aws:iam::123456789012:user/dev-bob");

        assertThat(prefixA).isNotEqualTo(prefixB);
    }

    /**
     * The runner reads DynamoDB since the cutover, and the table name arrives from a stack output
     * — nothing writes {@code /<prefix>/mongo/connection-string} any more. A scripted
     * {@code baas admin setup --mongo-uri ...} must fail loudly rather than have the URI silently
     * ignored while runs quietly write somewhere else.
     */
    @Test
    void rejectsTheRemovedMongoUriOption() {
        CommandLine cmd = new CommandLine(new SetupCommand());

        assertThatThrownBy(() -> cmd.parseArgs("--mongo-uri", "mongodb+srv://user:pass@host/db"))
            .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
    }
}
