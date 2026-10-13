package pl.wsztajerowski.baas.commands.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;
import pl.wsztajerowski.baas.infra.BucketProbe;

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
     * stack owns — so setting and revoking it is something `baas admin deployment setup` has to be able to
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
     * One deployment serving two repositories is otherwise a template migration. The patterns
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
     * invocation named: `baas admin deployment setup --github-org ... --oidc-provider-arn ...` reported
     * success against a fresh stack and deployed a role CI could not assume. Found by pointing CI
     * at a freshly created deployment.
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

    // ─── Deployment naming ─────────────────────────────────────────────────────

    @Test
    void theDeploymentIsNamedAfterTheAccountAlone() {
        assertThat(SetupCommand.computePrefix("123456789012")).isEqualTo("baas-123456789012");
    }

    /** With nothing configured the name is derived; a named or the only deployment is used verbatim. */
    @Test
    void setupDerivesTheNameOnlyWhenNoneIsGiven() {
        var none = new pl.wsztajerowski.baas.config.BaasConfig();
        var named = new pl.wsztajerowski.baas.config.BaasConfig();
        named.setPrefix("wiktor-dev");

        assertThat(SetupCommand.deploymentName(none, "123456789012")).isEqualTo("baas-123456789012");
        assertThat(SetupCommand.deploymentName(named, "123456789012")).isEqualTo("wiktor-dev");
    }

    /**
     * The point of the change: the identity holding the credentials must not reach the name. An
     * IAM user, an SSO session and a role-chained session on one account all address the same
     * deployment.
     */
    @Test
    void theCallerIdentityNeverReachesThePrefix() {
        assertThat(SetupCommand.computePrefix("123456789012"))
            .isEqualTo(SetupCommand.computePrefix("123456789012"));
    }

    @Test
    void differentAccountsAreDifferentDeployments() {
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

    /** Onboarding steps are true only of a stack this job created (U17). */
    @Test
    void anUpdateDoesNotClaimTheOperatorRoleWasJustCreated() {
        String arn = "arn:aws:iam::123456789012:role/baas-123456789012-role-operator";

        assertThat(SetupCommand.nextSteps(false, arn, "baas-123456789012"))
            .contains("is deployed", arn)
            .doesNotContain("created", "Nobody can assume", "build-image");
        assertThat(SetupCommand.nextSteps(true, arn, "baas-123456789012"))
            .contains("BaasCliOperatorRole created: " + arn, "Your deployer credentials already run",
                "baas --deployment baas-123456789012 config sync --operator-aws-profile",
                "baas admin image build", "/baas-123456789012/runner/ami-id")
            .doesNotContain("source_profile", "Nobody can assume");
    }

    // ─── an existing bucket blocking a create (U23) ──────────────────────────────

    /** The account's deployment is elsewhere: deleting its bucket is exactly the wrong advice. */
    @Test
    void aBucketInAnotherRegionNamesThatRegionAndNeverAdvisesDeletingIt() {
        String message = SetupCommand.bucketBlocksSetup("baas-123456789012", "baas-123456789012",
            "eu-central-1", "us-east-1");

        assertThat(message)
            .contains("lives in eu-central-1, not us-east-1")
            .contains("baas admin deployment setup --region eu-central-1")
            .doesNotContain("rb")
            .doesNotContain("--force");
    }


    // ─── a taken bucket or table name, refused before a create (R16) ──────────────

    @Test
    void aLeftoverBucketInTheRegionIsNamedWithItsRemoval() {
        var message = SetupCommand.nameConflict(new BucketProbe.Reachable("us-east-1"), false,
            "baas-development", "us-east-1");

        assertThat(message).hasValueSatisfying(m -> assertThat(m)
            .contains("Bucket baas-development already exists in us-east-1")
            .contains("aws s3 rb s3://baas-development --force")
            .contains("may hold earlier results")
            .contains("Nothing was deployed"));
    }

    @Test
    void anotherAccountsBucketAsksForAnotherName() {
        var message = SetupCommand.nameConflict(new BucketProbe.Forbidden("us-west-2"), false,
            "wiktor-dev", "us-east-1");

        assertThat(message).hasValueSatisfying(m -> assertThat(m)
            .contains("taken by another AWS account")
            .contains("baas --deployment <name> admin deployment setup")
            .doesNotContain("rb ")
            .doesNotContain("lives in"));
    }

    @Test
    void aLeftoverTableIsNamedWithItsRemoval() {
        var message = SetupCommand.nameConflict(new BucketProbe.Absent(), true, "baas-development", "us-east-1");

        assertThat(message).hasValueSatisfying(m -> assertThat(m)
            .contains("Table baas-development-results already exists in us-east-1")
            .contains("aws dynamodb delete-table --table-name baas-development-results --region us-east-1"));
    }

    @Test
    void freeNamesRaiseNoConflict() {
        assertThat(SetupCommand.nameConflict(new BucketProbe.Absent(), false, "d", "us-east-1")).isEmpty();
    }

    /** A bucket elsewhere is the early check's to report: the deployment lives there. */
    @Test
    void aReachableBucketInAnotherRegionIsNotACreateConflict() {
        assertThat(SetupCommand.nameConflict(new BucketProbe.Reachable("eu-central-1"), false, "d", "us-east-1"))
            .isEmpty();
    }

    // ─── The deployer policy is a step of setup ──────────────────────────────────

    @Test
    void missingRightsPrintThePolicyAsTheOnlyPayloadAndExitOne() {
        var out = new java.io.StringWriter();
        var command = new SetupCommand();
        new CommandLine(command).setOut(new java.io.PrintWriter(out, true));

        Integer exit = command.refusalForMissingRights(java.util.List.of("cloudformation:CreateStack"),
            "{\"Version\":\"2012-10-17\"}", "123456789012", "eu-central-1", "baas-123456789012", USER);

        assertThat(exit).isEqualTo(1);
        assertThat(out.toString().strip()).isEqualTo("{\"Version\":\"2012-10-17\"}");
    }

    @Test
    void sufficientOrUncheckableRightsCarryOn() {
        assertThat(new SetupCommand().refusalForMissingRights(java.util.List.of(), "{}", "1", "r", "d", USER)).isNull();
    }

    private static final String USER = "arn:aws:iam::381492019823:user/baas-admin-wiktor";

    /**
     * 7.1's AssumeOwnOperatorRole, the third statement a stale policy slipped past: with the
     * probes derived from the policy, a caller lacking only it is refused before anything exists.
     */
    @Test
    void aStatementNewerThanTheAttachedPolicyIsCaught() {
        String policy = new pl.wsztajerowski.baas.infra.DeployerPolicyRenderer()
            .render("381492019823", "us-east-1", "baas-development");
        var denied = new pl.wsztajerowski.baas.infra.DeployerPreflight(new DenyingIam("sts:AssumeRole"))
            .simulate(USER, policy);
        var command = new SetupCommand();
        new CommandLine(command).setOut(new java.io.PrintWriter(new java.io.StringWriter(), true));

        assertThat(denied).containsExactly("sts:AssumeRole");
        assertThat(command.refusalForMissingRights(denied, policy, "381492019823", "us-east-1",
            "baas-development", USER)).isEqualTo(1);
    }

    @Test
    void theAdministratorsCommandsAreFilledInForAUser() {
        String commands = SetupCommand.administratorCommands("381492019823", "baas-development", USER, "p.json");

        assertThat(commands)
            .contains("aws iam create-policy --policy-name BaasCliDeployerPolicy-baas-development --policy-document file://p.json")
            .contains("aws iam attach-user-policy --user-name baas-admin-wiktor "
                + "--policy-arn arn:aws:iam::381492019823:policy/BaasCliDeployerPolicy-baas-development")
            .contains("aws iam create-policy-version --policy-arn "
                + "arn:aws:iam::381492019823:policy/BaasCliDeployerPolicy-baas-development "
                + "--policy-document file://p.json --set-as-default");
    }

    @Test
    void anAssumedRoleGetsARolePolicyAttachment() {
        assertThat(SetupCommand.administratorCommands("381492019823", "d",
            "arn:aws:sts::381492019823:assumed-role/platform-admin/alice", "p.json"))
            .contains("aws iam attach-role-policy --role-name platform-admin");
    }

    /** IAM Identity Center owns its roles: the policy goes into the permission set. */
    @Test
    void anIdentityCenterRoleIsPointedAtItsPermissionSet() {
        assertThat(SetupCommand.administratorCommands("381492019823", "d",
            "arn:aws:sts::381492019823:assumed-role/AWSReservedSSO_Admin_0123abcd/alice", "p.json"))
            .contains("IAM Identity Center")
            .contains("permission set")
            .doesNotContain("attach-role-policy");
    }

    @Test
    void thePolicyFileHoldsTheRenderedPolicy(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var file = dir.resolve("p.json");
        var command = parsed("--policy-file", file.toString());

        assertThat(command.writePolicyFile("{\"Version\":\"2012-10-17\"}")).isTrue();
        assertThat(java.nio.file.Files.readString(file).strip()).isEqualTo("{\"Version\":\"2012-10-17\"}");
    }

    @Test
    void anUnwritablePolicyFileStopsSetup(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        var command = parsed("--policy-file", dir.resolve("missing-directory").resolve("p.json").toString());

        assertThat(command.writePolicyFile("{}")).isFalse();
    }

    /** With --policy-file the hand-over names the file; stdout still holds only the policy. */
    @Test
    void aRefusalWithAPolicyFileKeepsThePolicyAsTheOnlyPayload(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        var out = new java.io.StringWriter();
        var command = parsed("--policy-file", dir.resolve("p.json").toString());
        new CommandLine(command).setOut(new java.io.PrintWriter(out, true));

        command.refusalForMissingRights(java.util.List.of("iam:CreateRole"), "{\"Version\":\"2012-10-17\"}",
            "381492019823", "us-east-1", "baas-development", USER);

        assertThat(out.toString().strip()).isEqualTo("{\"Version\":\"2012-10-17\"}");
    }

    /** Denies exactly one action, allowing every other simulation. */
    private static final class DenyingIam implements software.amazon.awssdk.services.iam.IamClient {
        private final String deniedAction;

        DenyingIam(String deniedAction) {
            this.deniedAction = deniedAction;
        }

        @Override
        public software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyResponse simulatePrincipalPolicy(
            software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyRequest request) {
            String action = request.actionNames().getFirst();
            return software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyResponse.builder()
                .evaluationResults(software.amazon.awssdk.services.iam.model.EvaluationResult.builder()
                    .evalActionName(action).evalDecision(action.equals(deniedAction) ? "implicitDeny" : "allowed").build())
                .build();
        }

        @Override
        public String serviceName() {
            return "iam";
        }

        @Override
        public void close() {
        }
    }
}
