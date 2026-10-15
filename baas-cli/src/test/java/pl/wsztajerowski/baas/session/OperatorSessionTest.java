package pl.wsztajerowski.baas.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.wsztajerowski.baas.TestDeployments;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.sts.model.StsException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Paths A / B / C, the deployer fallback that only ever assumes the operator role, naming a
 * deployment from its operator role, and the region — against a stand-in for STS and S3.
 */
class OperatorSessionTest {

    private static final String ACCOUNT = "123456789012";
    private static final String USER = "arn:aws:iam::" + ACCOUNT + ":user/someone";

    @TempDir
    Path dir;

    /** Records every call; answers as told. */
    static final class FakeAws implements OperatorSession.Aws {
        String callerArn = USER;
        boolean canAssume = true;
        String bucketRegion = "eu-central-1";
        final List<String> calls = new ArrayList<>();
        final List<AwsCredentialsProvider> sources = new ArrayList<>();
        final AwsCredentialsProvider assumed =
            StaticCredentialsProvider.create(AwsBasicCredentials.create("ASSUMED", "secret"));

        @Override
        public OperatorSession.Caller callerIdentity(AwsCredentialsProvider source, String region) {
            calls.add("GetCallerIdentity");
            sources.add(source);
            return new OperatorSession.Caller(callerArn, ACCOUNT);
        }

        @Override
        public AwsCredentialsProvider assume(AwsCredentialsProvider source, String region, String roleArn) {
            calls.add("AssumeRole " + roleArn);
            sources.add(source);
            if (!canAssume) {
                throw StsException.builder().message("not authorized to perform sts:AssumeRole")
                    .awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                        .errorCode("AccessDenied").build())
                    .build();
            }
            return assumed;
        }

        @Override
        public Optional<String> bucketRegion(AwsCredentialsProvider credentials, String bucket, String fromRegion) {
            calls.add("HeadBucket " + bucket + " from " + fromRegion);
            return Optional.ofNullable(bucketRegion);
        }
    }

    private final FakeAws aws = new FakeAws();

    private OperatorSession session(Optional<String> named) {
        return new OperatorSession(new ConfigService(dir, named), aws, Map.of());
    }

    private AwsCredentialsProvider credentials(OperatorSession session) {
        return session.operatorCredentials(session.config());
    }

    // ─── A / B / C ───────────────────────────────────────────────────────────────

    @Test
    void pathC_otherCredentialsAssumeTheOperatorRoleInTheCallersAccount() throws Exception {
        TestDeployments.write(dir, "wiktor-dev");

        assertThat(credentials(session(Optional.empty()))).isSameAs(aws.assumed);
        assertThat(aws.calls).containsExactly(
            "GetCallerIdentity", "AssumeRole arn:aws:iam::123456789012:role/wiktor-dev-role-operator");
    }

    @Test
    void pathA_anOperatorRoleSessionForThisDeploymentIsUsedAsIs() throws Exception {
        TestDeployments.write(dir, "wiktor-dev");
        aws.callerArn = "arn:aws:sts::" + ACCOUNT + ":assumed-role/wiktor-dev-role-operator/GitHubActions";

        AwsCredentialsProvider resolved = credentials(session(Optional.empty()));

        assertThat(resolved).isSameAs(aws.sources.getFirst());
        assertThat(aws.calls).containsExactly("GetCallerIdentity");
    }

    @Test
    void pathB_anotherDeploymentsOperatorRoleIsRefused() throws Exception {
        TestDeployments.write(dir, "wiktor-dev");
        aws.callerArn = "arn:aws:sts::" + ACCOUNT + ":assumed-role/baas-123456789012-role-operator/s";

        var session = session(Optional.of("wiktor-dev"));

        assertThatThrownBy(() -> credentials(session))
            .isInstanceOf(DeploymentSelectionException.class)
            .hasMessageContaining("'baas-123456789012'").hasMessageContaining("'wiktor-dev'");
        assertThat(aws.calls).containsExactly("GetCallerIdentity");
    }

    /** Spec: a failed assumption stops the command — nothing more is called, with any credentials. */
    @Test
    void aFailedAssumptionStopsBeforeAnyClientIsBuilt() throws Exception {
        TestDeployments.write(dir, "wiktor-dev");
        aws.canAssume = false;
        var factory = session(Optional.empty()).factory();

        assertThatThrownBy(factory::s3)
            .isInstanceOf(OperatorSession.OperatorRoleException.class)
            .hasMessageContaining("arn:aws:iam::123456789012:role/wiktor-dev-role-operator")
            .hasMessageContaining("the default credential chain");
        assertThatThrownBy(factory::dynamoDb).isInstanceOf(OperatorSession.OperatorRoleException.class);
        assertThat(aws.calls).allMatch(call -> call.startsWith("GetCallerIdentity") || call.startsWith("AssumeRole"));
    }

    // ─── The source ──────────────────────────────────────────────────────────────

    /** Spec: the deployer profile is narrowed, never used for a day-to-day call. */
    @Test
    void theDeployerProfileIsOnlyTheSourceOfTheAssumption() throws Exception {
        TestDeployments.writeRaw(dir, "wiktor-dev", "aws:\n  deployerProfile: \"baas-admin\"\n  region: \"us-east-1\"\n");

        AwsCredentialsProvider resolved = credentials(session(Optional.empty()));

        assertThat(resolved).isSameAs(aws.assumed);
        assertThat(aws.sources).allSatisfy(source ->
            assertThat(source).isInstanceOf(ProfileCredentialsProvider.class)
                .asString().contains("baas-admin"));
    }

    /**
     * 7.1 added AssumeOwnOperatorRole to the deployer policy; one attached before that lacks it,
     * and setup's preflight passed it at the time. The denial says so, and how to get the new one.
     */
    @Test
    void aDeployerDeniedTheAssumptionIsPointedAtAStalePolicy() throws Exception {
        TestDeployments.writeRaw(dir, "wiktor-dev", "aws:\n  deployerProfile: \"baas-admin\"\n  region: \"us-east-1\"\n");
        aws.canAssume = false;

        assertThatThrownBy(() -> credentials(session(Optional.empty())))
            .isInstanceOf(OperatorSession.OperatorRoleException.class)
            .hasMessageContaining("profile baas-admin (aws.deployerProfile)")
            .hasMessageContaining("may predate this CLI")
            .hasMessageContaining("baas --deployment wiktor-dev admin deployment setup --policy-file wiktor-dev-deployer-policy.json");
    }

    /** A teammate's missing grant is their own, not the deployer policy's: no hint. */
    @Test
    void anOperatorProfileDeniedTheAssumptionGetsNoPolicyHint() throws Exception {
        TestDeployments.writeRaw(dir, "wiktor-dev",
            "aws:\n  deployerProfile: \"baas-admin\"\n  operatorProfile: \"teammate\"\n  region: \"us-east-1\"\n");
        aws.canAssume = false;

        assertThatThrownBy(() -> credentials(session(Optional.empty())))
            .isInstanceOf(OperatorSession.OperatorRoleException.class)
            .hasMessageContaining("teammate")
            .hasMessageNotContaining("deployer policy");
    }

    @Test
    void theOperatorProfileWinsOverTheDeployerProfile() throws Exception {
        TestDeployments.writeRaw(dir, "wiktor-dev",
            "aws:\n  deployerProfile: \"baas-admin\"\n  operatorProfile: \"teammate\"\n  region: \"us-east-1\"\n");

        credentials(session(Optional.empty()));

        assertThat(aws.sources).allSatisfy(source ->
            assertThat(source).asString().contains("teammate").doesNotContain("baas-admin"));
    }

    // ─── The deployment's name ───────────────────────────────────────────────────

    @Test
    void withNothingConfiguredAnOperatorRoleNamesTheDeployment() {
        aws.callerArn = "arn:aws:sts::" + ACCOUNT + ":assumed-role/baas-123456789012-role-operator/GitHubActions";

        BaasConfig config = session(Optional.empty()).config();

        assertThat(config.getPrefix()).isEqualTo("baas-123456789012");
        assertThat(dir.resolve("deployments")).doesNotExist();
    }

    @Test
    void withNothingConfiguredOtherCredentialsNameNothing() {
        assertThatThrownBy(() -> session(Optional.empty()).config())
            .isInstanceOf(DeploymentSelectionException.class)
            .hasMessageContaining("No deployment is configured")
            .hasMessageContaining("--deployment");
    }

    /** With files present the role never selects; it can only be refused by B. */
    @Test
    void withADeploymentConfiguredTheRoleSelectsNothing() throws Exception {
        TestDeployments.write(dir, "wiktor-dev");
        TestDeployments.write(dir, "other");
        aws.callerArn = "arn:aws:sts::" + ACCOUNT + ":assumed-role/wiktor-dev-role-operator/s";

        assertThatThrownBy(() -> session(Optional.empty()).config())
            .isInstanceOf(DeploymentSelectionException.class)
            .hasMessageContaining("2 deployments are configured");
        assertThat(aws.calls).isEmpty();
    }

    @Test
    void aFileIsReadWithoutAnyAwsCall() throws Exception {
        TestDeployments.write(dir, "wiktor-dev");

        var session = session(Optional.empty());
        session.config();
        session.factory();

        assertThat(aws.calls).isEmpty();
    }

    // ─── The region ──────────────────────────────────────────────────────────────

    @Test
    void theFilesRegionIsUsedWithNoLookup() throws Exception {
        TestDeployments.writeRaw(dir, "wiktor-dev", "aws:\n  region: \"us-east-1\"\n");

        assertThat(session(Optional.empty()).factory().region()).isEqualTo("us-east-1");
        assertThat(aws.calls).noneMatch(call -> call.startsWith("HeadBucket"));
    }

    /** Spec: with no file the region is the bucket's, whatever AWS_REGION says. */
    @Test
    void withNoFileTheRegionIsTheBucketsNotTheEnvironments() {
        aws.callerArn = "arn:aws:sts::" + ACCOUNT + ":assumed-role/baas-123456789012-role-operator/s";
        aws.bucketRegion = "eu-central-1";
        var session = new OperatorSession(new ConfigService(dir, Optional.empty()), aws, Map.of("AWS_REGION", "us-east-1"));

        assertThat(session.factory().region()).isEqualTo("eu-central-1");
        assertThat(aws.calls).contains("HeadBucket baas-123456789012 from us-east-1");
    }

    @Test
    void withNoFileAMissingBucketIsNoDeployment() {
        aws.callerArn = "arn:aws:sts::" + ACCOUNT + ":assumed-role/ghost-role-operator/s";
        aws.bucketRegion = null;
        var factory = session(Optional.empty()).factory();

        assertThatThrownBy(factory::region)
            .isInstanceOf(DeploymentSelectionException.class)
            .hasMessageContaining("No deployment named 'ghost'");
    }
}
