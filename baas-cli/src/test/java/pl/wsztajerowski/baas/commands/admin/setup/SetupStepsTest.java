package pl.wsztajerowski.baas.commands.admin.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.wsztajerowski.baas.TestDeployments;
import pl.wsztajerowski.baas.infra.BucketProbe;
import pl.wsztajerowski.baas.infra.CloudFormationService;
import software.amazon.awssdk.services.cloudformation.model.StackStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The walkthrough's steps against {@link FakeSetupAws}: what each asks, what it does, where it
 * waits or stops, and what lands in the deployment's file.
 */
class SetupStepsTest {

    @TempDir
    Path root;

    private static final String DERIVED = "baas-" + FakeSetupAws.ACCOUNT;

    private SetupRun setup() {
        return new SetupRun(root);
    }

    /** A deployment configured on this machine whose stack exists and is current. */
    private SetupRun existing(String name, String yaml) throws Exception {
        TestDeployments.writeRaw(root, name, yaml);
        var run = setup();
        run.aws.stacks.put(name, StackStatus.CREATE_COMPLETE);
        run.aws.stackParameters.put(name, Map.of("ResourceNamePrefix", name));
        run.aws.image = new SetupAws.Image.Current("1.3.0");
        return run;
    }

    private static final String DEV_FILE = "aws:\n  deployerProfile: \"baas-admin\"\n  region: \"us-east-1\"\n";

    // ─── Step 1: which deployment ─────────────────────────────────────────────────

    @Test
    void aNewDeploymentAsksAwsNothingBeforeTheCredentialsStep() throws Exception {
        var run = setup();

        assertThat(run.run()).isZero();
        assertThat(run.aws.calls.getFirst()).isEqualTo("callerIdentity");
        assertThat(run.hasFile(DERIVED)).isTrue();
    }

    @Test
    void severalDeploymentsAreOfferedAsAChoice() throws Exception {
        TestDeployments.writeRaw(root, "alpha", DEV_FILE);
        TestDeployments.writeRaw(root, "beta", DEV_FILE);
        var run = setup().interactive("2", "", "");
        run.aws.stacks.put("beta", StackStatus.CREATE_COMPLETE);
        run.aws.image = new SetupAws.Image.Current("1.3.0");

        assertThat(run.run()).isZero();
        assertThat(run.asked("1) alpha")).isTrue();
        assertThat(run.asked("3) a new deployment")).isTrue();
        assertThat(run.aws.called("update:beta")).isTrue();
        assertThat(run.aws.calledAny("update:alpha")).isFalse();
    }

    @Test
    void severalDeploymentsUnattendedAreRefusedBeforeAnyAwsCall() throws Exception {
        TestDeployments.writeRaw(root, "alpha", DEV_FILE);
        TestDeployments.writeRaw(root, "beta", DEV_FILE);
        var run = setup();

        assertThat(run.run()).isEqualTo(2);
        assertThat(run.aws.calls).isEmpty();
    }

    @Test
    void anUnusableNameUnattendedIsRefusedBeforeAnyAwsCall() throws Exception {
        var run = setup().deployment("aws-dev");

        assertThat(run.run()).isEqualTo(2);
        assertThat(run.aws.calls).isEmpty();
    }

    // ─── Step 2: credentials ──────────────────────────────────────────────────────

    @Test
    void anExpiredSessionIsWaitedForInteractively() throws Exception {
        var run = setup().interactive("", "", "us-east-1", "", "", "", "", "");
        run.aws.identityFailures.add(FakeSetupAws.expiredSession());

        assertThat(run.run()).isZero();
        assertThat(run.aws.calls).filteredOn("callerIdentity"::equals).hasSize(2);
        assertThat(run.asked("Press Enter to check again")).isTrue();
    }

    @Test
    void anExpiredSessionUnattendedStopsWithExitOne() throws Exception {
        var run = setup();
        run.aws.identityFailures.add(FakeSetupAws.expiredSession());

        assertThat(run.run()).isEqualTo(1);
        assertThat(CredentialsStep.explain("baas-admin", FakeSetupAws.expiredSession()))
            .contains("aws sso login --profile baas-admin");
    }

    @Test
    void theStoredProfileIsTheDefault() throws Exception {
        var run = existing("dev", DEV_FILE).interactive("", "");

        assertThat(run.run()).isZero();
        assertThat(run.asked("Deployer AWS profile (empty for the default credentials) [baas-admin]")).isTrue();
        assertThat(run.aws.builtFor).contains("us-east-1/baas-admin");
    }

    // ─── Step 3: name and region ──────────────────────────────────────────────────

    @Test
    void theSuggestedNameIsTheDefault() throws Exception {
        var run = setup().interactive("", "", "us-east-1", "", "", "");

        assertThat(run.run()).isZero();
        assertThat(run.asked("Deployment name [" + DERIVED + "]")).isTrue();
        assertThat(run.hasFile(DERIVED)).isTrue();
    }

    @Test
    void anyValidNameIsAcceptedAndAnInvalidOneAskedAgain() throws Exception {
        var run = setup().interactive("", "aws-bench", "team-bench", "us-east-1", "", "", "");

        assertThat(run.run()).isZero();
        assertThat(run.hasFile("team-bench")).isTrue();
        assertThat(run.out.toString()).contains("'aws-bench'");
        assertThat(run.deploymentFile("team-bench")).contains("us-east-1");
    }

    @Test
    void anExistingDeploymentsNameAndRegionAreNotAsked() throws Exception {
        var run = existing("dev", DEV_FILE).interactive("", "");

        assertThat(run.run()).isZero();
        assertThat(run.asked("Deployment name")).isFalse();
        assertThat(run.asked("AWS region")).isFalse();
    }

    @Test
    void aDeploymentLivingInAnotherRegionStopsSetup() throws Exception {
        var run = setup();
        run.region = "us-east-1";
        run.aws.buckets.put(DERIVED, new BucketProbe.Reachable("eu-central-1"));

        assertThat(run.run()).isEqualTo(1);
        assertThat(run.aws.calledAny("preflight")).isFalse();
    }

    @Test
    void aBucketInAnotherRegionNamesThatRegionAndNeverAdvisesDeletingIt() {
        String message = NameAndRegionStep.bucketBlocksSetup("baas-123456789012", "baas-123456789012",
            "eu-central-1", "us-east-1");

        assertThat(message)
            .contains("lives in eu-central-1, not us-east-1")
            .contains("baas --deployment baas-123456789012 admin deployment setup --region eu-central-1")
            .doesNotContain("rb")
            .doesNotContain("--force");
    }

    @Test
    void theDerivedNameIsTheAccountAlone() {
        assertThat(NameAndRegionStep.computePrefix("123456789012")).isEqualTo("baas-123456789012");
        assertThat(NameAndRegionStep.computePrefix("123456789012"))
            .isNotEqualTo(NameAndRegionStep.computePrefix("210987654321"));
    }

    @Test
    void anAccountThatIsNotTwelveDigitsIsRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> NameAndRegionStep.computePrefix("not-an-account"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not-an-account");
    }

    // ─── Step 4: permissions ──────────────────────────────────────────────────────

    @Test
    void missingRightsUnattendedPrintOnlyThePolicyAndExitOne() throws Exception {
        var run = setup();
        run.aws.preflights.clear();
        run.aws.preflights.add(new SetupAws.Preflight.Denied(List.of("cloudformation:CreateStack")));

        assertThat(run.run()).isEqualTo(1);
        assertThat(run.out.toString().strip()).startsWith("{").endsWith("}").contains("\"Version\"");
        assertThat(run.aws.calledAny("submitCreate")).isFalse();
        assertThat(run.hasFile(DERIVED)).isFalse();
    }

    @Test
    void missingRightsAreSavedAndWaitedForInteractively() throws Exception {
        Path saved = root.resolve("p.json");
        var run = setup().interactive("", "", "us-east-1", saved.toString(), "", "", "", "");
        run.aws.preflights.clear();
        run.aws.preflights.add(new SetupAws.Preflight.Denied(List.of("sts:AssumeRole")));
        run.aws.preflights.add(new SetupAws.Preflight.Met());

        assertThat(run.run()).isZero();
        assertThat(run.asked("Save the deployer policy to a file, or type - to print it [policy.json]")).isTrue();
        assertThat(Files.readString(saved)).contains("\"Version\"", DERIVED);
        assertThat(run.aws.calls).filteredOn("preflight"::equals).hasSize(2);
        assertThat(run.aws.called("submitCreate:" + DERIVED)).isTrue();
    }

    @Test
    void missingRightsCanBePrintedInstead() throws Exception {
        var run = setup().interactive("", "", "us-east-1", "-", "", "", "", "");
        run.aws.preflights.clear();
        run.aws.preflights.add(new SetupAws.Preflight.Denied(List.of("sts:AssumeRole")));
        run.aws.preflights.add(new SetupAws.Preflight.Met());

        assertThat(run.run()).isZero();
        assertThat(run.out.toString()).contains("\"Statement\"");
    }

    @Test
    void thePolicyFileOptionAnswersTheQuestion() throws Exception {
        var run = setup().interactive("", "", "us-east-1", "", "", "", "");
        run.policyFile = root.resolve("given.json");
        run.aws.preflights.clear();
        run.aws.preflights.add(new SetupAws.Preflight.Denied(List.of("sts:AssumeRole")));
        run.aws.preflights.add(new SetupAws.Preflight.Met());

        assertThat(run.run()).isZero();
        assertThat(run.asked("Save the deployer policy")).isFalse();
        assertThat(run.policyFile).exists();
    }

    @Test
    void aCheckThatCannotRunIsNotMetButTheRunContinues() throws Exception {
        var run = setup();
        run.aws.preflights.clear();
        run.aws.preflights.add(new SetupAws.Preflight.NotChecked());

        assertThat(run.run()).isZero();
        assertThat(run.aws.called("submitCreate:" + DERIVED)).isTrue();
    }

    @Test
    void anUnwritablePolicyFileStopsSetupBeforeAnythingIsCreated() throws Exception {
        var run = setup();
        run.policyFile = root.resolve("missing-directory").resolve("p.json");

        assertThat(run.run()).isEqualTo(1);
        assertThat(run.aws.calledAny("submitCreate")).isFalse();
    }

    @Test
    void theAdministratorsCommandsAreFilledInForAUser() {
        String commands = PolicyHandOver.administratorCommands("381492019823", "baas-development",
            "arn:aws:iam::381492019823:user/baas-admin-wiktor", "p.json");

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
        assertThat(PolicyHandOver.administratorCommands("381492019823", "d",
            "arn:aws:sts::381492019823:assumed-role/platform-admin/alice", "p.json"))
            .contains("aws iam attach-role-policy --role-name platform-admin");
    }

    @Test
    void anIdentityCenterRoleIsPointedAtItsPermissionSet() {
        assertThat(PolicyHandOver.administratorCommands("381492019823", "d",
            "arn:aws:sts::381492019823:assumed-role/AWSReservedSSO_Admin_0123abcd/alice", "p.json"))
            .contains("IAM Identity Center")
            .contains("permission set")
            .doesNotContain("attach-role-policy");
    }

    // ─── Step 5: names ────────────────────────────────────────────────────────────

    /** The name is asked again, and the new name's policy is checked before its names are. */
    @Test
    void aNameAnotherAccountHoldsIsAskedAgainAndCheckedAgain() throws Exception {
        var run = setup().interactive("", "wiktor-dev", "us-east-1", "team-bench", "", "", "");
        run.aws.buckets.put("wiktor-dev", new BucketProbe.Forbidden("us-west-2"));

        assertThat(run.run()).isZero();
        assertThat(run.hasFile("team-bench")).isTrue();
        assertThat(run.hasFile("wiktor-dev")).isFalse();
        assertThat(run.aws.calls).containsSubsequence(
            "bucket:wiktor-dev", "preflight", "bucket:wiktor-dev",
            "bucket:team-bench", "preflight", "bucket:team-bench", "submitCreate:team-bench");
    }

    @Test
    void aNameAnotherAccountHoldsUnattendedExitsOne() throws Exception {
        var run = setup().deployment("wiktor-dev");
        run.aws.buckets.put("wiktor-dev", new BucketProbe.Forbidden("us-west-2"));

        assertThat(run.run()).isEqualTo(1);
        assertThat(run.aws.calledAny("submitCreate")).isFalse();
    }

    @Test
    void aLeftoverIsNamedAndNeverDeleted() throws Exception {
        var run = setup();
        run.aws.tables.add(DERIVED + "-results");

        assertThat(run.run()).isEqualTo(1);
        assertThat(run.aws.calledAny("submitCreate")).isFalse();
        assertThat(run.aws.calledAny("deleteStack")).isFalse();
    }

    @Test
    void aRolledBackStackWithALeftoverIsLeftInPlace() throws Exception {
        var run = setup();
        run.aws.stacks.put(DERIVED, StackStatus.ROLLBACK_COMPLETE);
        run.aws.buckets.put(DERIVED, new BucketProbe.Reachable("eu-central-1"));
        run.region = "eu-central-1";

        assertThat(run.run()).isEqualTo(1);
        assertThat(run.aws.calledAny("deleteStack")).isFalse();
    }

    @Test
    void nameConflictsAreNamedWithTheirRemedy() {
        assertThat(NamesStep.nameConflict(new BucketProbe.Reachable("us-east-1"), false, "baas-development", "us-east-1"))
            .hasValueSatisfying(m -> assertThat(m).contains("aws s3 rb s3://baas-development --force"));
        assertThat(NamesStep.nameConflict(new BucketProbe.Forbidden("us-west-2"), false, "wiktor-dev", "us-east-1"))
            .hasValueSatisfying(m -> assertThat(m).contains("taken by another AWS account").doesNotContain("rb "));
        assertThat(NamesStep.nameConflict(new BucketProbe.Absent(), true, "baas-development", "us-east-1"))
            .hasValueSatisfying(m -> assertThat(m)
                .contains("aws dynamodb delete-table --table-name baas-development-results --region us-east-1"));
        assertThat(NamesStep.nameConflict(new BucketProbe.Absent(), false, "d", "us-east-1")).isEmpty();
        assertThat(NamesStep.nameConflict(new BucketProbe.Reachable("eu-central-1"), false, "d", "us-east-1")).isEmpty();
    }

    // ─── Step 6: GitHub Actions ───────────────────────────────────────────────────

    @Test
    void federationSkippedChecksNoProvider() throws Exception {
        var run = setup().interactive("", "", "us-east-1", "n", "", "");

        assertThat(run.run()).isZero();
        assertThat(run.aws.calledAny("oidcProvider")).isFalse();
        assertThat(run.aws.submitted).containsEntry("GitHubOrg", "").containsEntry("GitHubRepo", "");
    }

    @Test
    void anAbsentProviderIsCreatedBeforeTheStack() throws Exception {
        var run = setup();
        run.githubOrg = "acme";
        run.githubRepos = List.of("widgets");

        assertThat(run.run()).isZero();
        assertThat(run.aws.calls).containsSubsequence(
            "oidcProvider:" + SetupAws.providerArn(FakeSetupAws.ACCOUNT), "createOidcProvider", "submitCreate:" + DERIVED);
        assertThat(run.aws.submitted)
            .containsEntry("GitHubOrg", "acme")
            .containsEntry("GitHubRepo", "repo:acme/widgets:*")
            .doesNotContainKey("GitHubOidcProviderArn");
    }

    @Test
    void anExistingProviderIsReused() throws Exception {
        var run = setup();
        run.githubOrg = "acme";
        run.githubRepos = List.of("widgets");
        run.aws.provider = new SetupAws.OidcProvider.Present(List.of(SetupAws.STS_AUDIENCE));

        assertThat(run.run()).isZero();
        assertThat(run.aws.calledAny("createOidcProvider")).isFalse();
    }

    @Test
    void aProviderWithoutTheAudienceIsLeftToAnAdministrator() throws Exception {
        var run = setup();
        run.githubOrg = "acme";
        run.githubRepos = List.of("widgets");
        run.aws.provider = new SetupAws.OidcProvider.Present(List.of("other-audience"));

        assertThat(run.run()).isEqualTo(1);
        assertThat(run.aws.calledAny("createOidcProvider")).isFalse();
        assertThat(run.aws.calledAny("submitCreate")).isFalse();
    }

    @Test
    void aRepositoryIsAddedOnUpdate() throws Exception {
        var run = existing("dev", DEV_FILE).interactive("", "y", "", "a,b", "", "");
        run.aws.stackParameters.put("dev", Map.of("ResourceNamePrefix", "dev",
            "GitHubOrg", "org", "GitHubRepo", "repo:org/a:*"));
        run.aws.provider = new SetupAws.OidcProvider.Present(List.of(SetupAws.STS_AUDIENCE));

        assertThat(run.run()).isZero();
        assertThat(run.asked("GitHub Actions: org/a may run benchmarks")).isTrue();
        assertThat(run.aws.submitted).containsEntry("GitHubRepo", "repo:org/a:*,repo:org/b:*");
    }

    /** Carry-forward: an unrelated setup must not resubmit the defaults that revoked CI's access. */
    @Test
    void unchangedFederationIsCarriedForward() throws Exception {
        var run = existing("dev", DEV_FILE).interactive("", "", "");
        run.aws.stackParameters.put("dev", Map.of("ResourceNamePrefix", "dev",
            "GitHubOrg", "org", "GitHubRepo", "repo:org/a:*"));
        run.aws.provider = new SetupAws.OidcProvider.Present(List.of(SetupAws.STS_AUDIENCE));

        assertThat(run.run()).isZero();
        assertThat(run.aws.submitted).doesNotContainKeys("GitHubOrg", "GitHubRepo");
    }

    @Test
    void revocationSubmitsBothEmpty() throws Exception {
        var run = existing("dev", DEV_FILE);
        run.revoke = true;
        run.aws.stackParameters.put("dev", Map.of("GitHubOrg", "org", "GitHubRepo", "repo:org/a:*"));

        assertThat(run.run()).isZero();
        assertThat(run.aws.submitted).containsEntry("GitHubOrg", "").containsEntry("GitHubRepo", "");
        assertThat(run.aws.calledAny("oidcProvider")).isFalse();
    }

    @Test
    void theDeployedTrustIsReadBackFromTheStack() {
        assertThat(GitHubStep.deployed(Map.of("GitHubOrg", "acme", "GitHubRepo", "repo:acme/one:*,repo:acme/two:*")))
            .contains(new SetupContext.Federation("acme", List.of("one", "two")));
        assertThat(GitHubStep.deployed(Map.of("GitHubOrg", "", "GitHubRepo", ""))).isEmpty();
        assertThat(GitHubStep.subjectPatterns("acme", List.of("one", " two "))).isEqualTo("repo:acme/one:*,repo:acme/two:*");
    }

    // ─── Step 7: plan ─────────────────────────────────────────────────────────────

    @Test
    void decliningThePlanChangesNothing() throws Exception {
        var run = setup().interactive("", "", "us-east-1", "y", "acme", "widgets", "n");

        assertThat(run.run()).isEqualTo(1);
        assertThat(run.asked("about $0.20 a month")).isTrue();
        assertThat(run.aws.calledAny("submitCreate")).isFalse();
        assertThat(run.aws.calledAny("createOidcProvider")).isFalse();
        assertThat(run.hasFile(DERIVED)).isFalse();
    }

    @Test
    void aTemplateOnlyUpdateIsNotConfirmed() throws Exception {
        var run = existing("dev", DEV_FILE).interactive("", "");

        assertThat(run.run()).isZero();
        assertThat(run.asked("Go ahead?")).isFalse();
        assertThat(run.aws.called("update:dev")).isTrue();
    }

    // ─── Step 8: stack ────────────────────────────────────────────────────────────

    @Test
    void aCreateStillInProgressIsWaitedForAndThenUpdated() throws Exception {
        var run = existing("dev", DEV_FILE);
        run.aws.stacks.put("dev", StackStatus.CREATE_IN_PROGRESS);

        assertThat(run.run()).isZero();
        assertThat(run.aws.calls).containsSubsequence("awaitSettled:dev", "update:dev");
        assertThat(run.aws.calledAny("submitCreate")).isFalse();
    }

    @Test
    void aRolledBackFirstCreateIsDeletedAndCreatedAgain() throws Exception {
        var run = setup();
        run.aws.stacks.put(DERIVED, StackStatus.ROLLBACK_COMPLETE);

        assertThat(run.run()).isZero();
        assertThat(run.aws.calls).containsSubsequence("deleteStack:" + DERIVED, "submitCreate:" + DERIVED);
    }

    /** D20: written once the create is accepted, so an interrupted or failed create leaves a file. */
    @Test
    void theFileIsWrittenOnceTheCreateIsAccepted() throws Exception {
        var run = setup();
        run.region = "us-east-1";
        run.aws.awaitCreateFailure = new CloudFormationService.StackOperationException("rolled back", false, null);

        assertThat(run.run()).isEqualTo(1);
        assertThat(run.deploymentFile(DERIVED)).contains("us-east-1");
    }

    @Test
    void aRefusedCreateLeavesNoFile() throws Exception {
        var run = setup();
        run.aws.stackFailures.add(new CloudFormationService.StackOperationException("refused", false, null));

        assertThat(run.run()).isEqualTo(1);
        assertThat(run.hasFile(DERIVED)).isFalse();
    }

    @Test
    void anUnprovenProfileIsNotInTheEarlyFile() throws Exception {
        var run = setup();
        run.profile = "lynx";
        run.aws.preflights.clear();
        run.aws.preflights.add(new SetupAws.Preflight.NotChecked());
        run.aws.awaitCreateFailure = new CloudFormationService.StackOperationException("rolled back", false, null);

        assertThat(run.run()).isEqualTo(1);
        assertThat(run.deploymentFile(DERIVED)).doesNotContain("lynx");
    }

    @Test
    void aPermissionFailureInTheStackReturnsToThePermissionStep() throws Exception {
        Path saved = root.resolve("p.json");
        var run = setup().interactive("", "", "us-east-1", "", "", saved.toString(), "", "");
        run.aws.preflights.clear();
        run.aws.preflights.add(new SetupAws.Preflight.NotChecked());
        run.aws.preflights.add(new SetupAws.Preflight.Met());
        run.aws.stackFailures.add(new CloudFormationService.StackOperationException("not authorized", true, null));

        assertThat(run.run()).isZero();
        assertThat(saved).exists();
        assertThat(run.aws.calls).containsSubsequence("submitCreate:" + DERIVED, "preflight", "submitCreate:" + DERIVED);
        assertThat(run.prompts).filteredOn(prompt -> prompt.contains("Go ahead?")).hasSize(1);
    }

    @Test
    void aPermissionFailureInTheStackUnattendedExitsOne() throws Exception {
        var run = setup();
        run.aws.stackFailures.add(new CloudFormationService.StackOperationException("not authorized", true, null));

        assertThat(run.run()).isEqualTo(1);
        assertThat(run.out.toString()).contains("\"Statement\"");
    }

    /** Found live with lynx: a denial at a later step must hand the policy over, not end bare. */
    @Test
    void aDenialAtALaterStepHandsThePolicyOver() throws Exception {
        var run = existing("dev", DEV_FILE);
        run.aws.preflights.clear();
        run.aws.preflights.add(new SetupAws.Preflight.NotChecked());
        run.aws.imageFailure = software.amazon.awssdk.awscore.exception.AwsServiceException.builder()
            .message("not authorized to perform: imagebuilder:ListImagePipelineImages")
            .awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                .errorCode("AccessDeniedException").build())
            .build();

        assertThat(run.run()).isEqualTo(1);
        assertThat(run.out.toString().strip()).startsWith("{").endsWith("}").contains("\"Statement\"");
    }

    // ─── Step 9: operator role ────────────────────────────────────────────────────

    @Test
    void aNewRoleIsGivenTimeToPropagate() throws Exception {
        var run = setup();
        run.aws.assumeFailuresLeft = 2;

        assertThat(run.run()).isZero();
        assertThat(run.slept).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4));
    }

    @Test
    void aRoleThatStaysRefusedHandsThePolicyOver() throws Exception {
        var run = setup();
        run.aws.assumeFailuresLeft = 99;

        assertThat(run.run()).isEqualTo(1);
        assertThat(run.slept).hasSize(4);
        assertThat(run.out.toString()).contains("\"Statement\"");
    }

    // ─── Step 10: runner image ────────────────────────────────────────────────────

    @Test
    void aCurrentImageIsNotOffered() throws Exception {
        var run = existing("dev", DEV_FILE).interactive("", "");

        assertThat(run.run()).isZero();
        assertThat(run.asked("Build the runner image")).isFalse();
        assertThat(run.aws.calledAny("buildImage")).isFalse();
    }

    @Test
    void unattendedSetupNeverBuildsWithoutTheOption() throws Exception {
        var run = setup();

        assertThat(run.run()).isZero();
        assertThat(run.aws.calledAny("buildImage")).isFalse();
        assertThat(DoneStep.nextSteps(run.context)).contains("baas --deployment " + DERIVED + " admin image build");
    }

    @Test
    void theOfferAcceptedBuildsTheImage() throws Exception {
        var run = setup().interactive("", "", "us-east-1", "", "", "");

        assertThat(run.run()).isZero();
        assertThat(run.aws.called("buildImage:" + DERIVED)).isTrue();
        assertThat(DoneStep.nextSteps(run.context)).doesNotContain("admin image build");
    }

    @Test
    void theBuildImageOptionBuildsUnattended() throws Exception {
        var run = setup();
        run.buildImage = true;

        assertThat(run.run()).isZero();
        assertThat(run.aws.called("buildImage:" + DERIVED)).isTrue();
    }

    @Test
    void aRunningBuildCanBeLeftAlone() throws Exception {
        var run = existing("dev", DEV_FILE).interactive("", "n");
        run.aws.runningBuild = Optional.of(new SetupAws.RunningBuild(Instant.parse("2026-10-14T10:15:00Z")));

        assertThat(run.run()).isZero();
        assertThat(run.asked("is still running")).isTrue();
        assertThat(run.aws.calledAny("buildImage")).isFalse();
    }

    @Test
    void aRunningBuildIsWaitedForUnattended() throws Exception {
        var run = existing("dev", DEV_FILE);
        run.aws.runningBuild = Optional.of(new SetupAws.RunningBuild(Instant.parse("2026-10-14T10:15:00Z")));

        assertThat(run.run()).isZero();
        assertThat(run.aws.called("buildImage:dev")).isTrue();
    }

    // ─── Step 11: the stored profile ──────────────────────────────────────────────

    /** W2: lynx re-running a no-op setup replaced a working baas-admin. */
    @Test
    void anUnverifiableIdentityDoesNotReplaceAWorkingProfile() throws Exception {
        var run = existing("dev", DEV_FILE);
        run.profile = "lynx";
        run.aws.preflights.clear();
        run.aws.preflights.add(new SetupAws.Preflight.NotChecked());
        run.aws.updateChanges = false;

        assertThat(run.run()).isZero();
        assertThat(run.deploymentFile("dev")).contains("baas-admin").doesNotContain("lynx");
    }

    @Test
    void aVerifiedIdentityIsStoredEvenWhenNothingChanged() throws Exception {
        var run = existing("dev", DEV_FILE);
        run.profile = "other-admin";
        run.aws.updateChanges = false;

        assertThat(run.run()).isZero();
        assertThat(run.deploymentFile("dev")).contains("other-admin");
    }

    @Test
    void aCreateIsItsOwnEvidence() throws Exception {
        var run = setup();
        run.profile = "lynx";
        run.aws.preflights.clear();
        run.aws.preflights.add(new SetupAws.Preflight.NotChecked());

        assertThat(run.run()).isZero();
        assertThat(run.deploymentFile(DERIVED)).contains("lynx");
    }

    @Test
    void theNextStepsAlwaysNameTheDeployment() throws Exception {
        var run = setup();
        run.githubOrg = "acme";
        run.githubRepos = List.of("widgets");

        assertThat(run.run()).isZero();
        assertThat(DoneStep.nextSteps(run.context))
            .contains("baas --deployment " + DERIVED + " run --benchmark-jar")
            .contains("baas --deployment " + DERIVED + " config sync --operator-aws-profile")
            .contains("\"Action\": \"sts:AssumeRole\"")
            .contains("gh variable set OPERATOR_ROLE_ARN --body arn:aws:iam::" + FakeSetupAws.ACCOUNT
                + ":role/" + DERIVED + "-role-operator --repo acme/widgets")
            .contains("gh variable set AWS_REGION");
    }

    @Test
    void aHealthyReRunChangesNothing() throws Exception {
        var run = existing("dev", DEV_FILE).interactive("", "");
        run.aws.updateChanges = false;
        String before = run.deploymentFile("dev");

        assertThat(run.run()).isZero();
        assertThat(run.context.changedAnything()).isFalse();
        assertThat(run.deploymentFile("dev")).isEqualTo(before);
        assertThat(run.aws.calledAny("buildImage")).isFalse();
        assertThat(run.aws.calledAny("createOidcProvider")).isFalse();
    }
}
