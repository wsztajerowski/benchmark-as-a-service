package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.EvaluationResult;
import software.amazon.awssdk.services.iam.model.IamException;
import software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyRequest;
import software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class DeployerPreflightTest {

    private static final String CALLER = "arn:aws:iam::" + InfraFixtures.ACCOUNT_ID + ":user/deployer";

    private static String renderedPolicy() {
        return new DeployerPolicyRenderer().render(InfraFixtures.ACCOUNT_ID, InfraFixtures.REGION, InfraFixtures.PREFIX);
    }

    private static List<DeployerPreflight.Probe> probes() {
        return DeployerPreflight.probes(renderedPolicy());
    }

    /**
     * The probes are the policy: every unconditioned statement, and nothing else. A hand-kept list
     * used to stand here, and three times a release added a statement it did not cover —
     * dynamodb:CreateTable, iam:UpdateRole, and 7.1's sts:AssumeRole — so a stale policy passed.
     */
    @Test
    void probesEveryUnconditionedStatementAndNoConditionedOne() {
        Set<String> probed = probes().stream().map(DeployerPreflight.Probe::sid).collect(java.util.stream.Collectors.toSet());

        assertThat(probed).containsExactlyInAnyOrder(
            "CloudFormation", "SsmRunnerAmiPointer", "ImageBuilder", "PassRolesToServices",
            "AssumeOwnOperatorRole", "IAM", "PreflightSimulation", "ResultsTableLifecycle",
            "S3WorkingBucketLifecycle");
    }

    /**
     * The simulator has no value for aws:RequestedRegion or iam:AWSServiceName, so probing a
     * conditioned statement would report a denial that will not happen.
     */
    @Test
    void conditionedStatementsAreNotProbed() {
        assertThat(probes()).extracting(DeployerPreflight.Probe::sid)
            .doesNotContain("EC2", "CloudFormationValidateTemplate", "ImageBuilderRead",
                "ImageBuilderServiceLinkedRole");
    }

    /** Each probe is one action, carrying its statement's resources verbatim, patterns included. */
    @Test
    void eachProbeCarriesItsStatementsResourcesAsWritten() {
        var iam = probes().stream().filter(probe -> probe.action().equals("iam:CreateRole")).toList();

        assertThat(iam).singleElement().satisfies(probe -> assertThat(probe.resources()).contains(
            "arn:aws:iam::%s:role/%s-role-operator".formatted(InfraFixtures.ACCOUNT_ID, InfraFixtures.PREFIX),
            "arn:aws:iam::%s:instance-profile/%s-profile-runner".formatted(InfraFixtures.ACCOUNT_ID, InfraFixtures.PREFIX)));
        assertThat(probes()).filteredOn(probe -> probe.sid().equals("ImageBuilder"))
            .allSatisfy(probe -> assertThat(probe.resources()).allSatisfy(resource ->
                assertThat(resource).contains(InfraFixtures.PREFIX)));
    }

    /** The three actions a stale policy once slipped past are probed, against the right resources. */
    @Test
    void theActionsThatOnceSlippedPastAreProbed() {
        assertThat(probes()).anySatisfy(probe -> {
            assertThat(probe.action()).isEqualTo("dynamodb:CreateTable");
            assertThat(probe.resources()).anySatisfy(resource -> assertThat(resource).endsWith(":table/" + InfraFixtures.PREFIX + "-results"));
        });
        assertThat(probes()).extracting(DeployerPreflight.Probe::action)
            .contains("iam:UpdateAssumeRolePolicy", "iam:UpdateRole", "sts:AssumeRole");
    }

    @Test
    void aDeniedActionIsReportedOnceAndAllowedOnesNot() {
        var iam = new FakeIam(request -> request.actionNames().contains("sts:AssumeRole"));

        var denied = new DeployerPreflight(iam).simulate(CALLER, renderedPolicy());

        assertThat(denied).containsExactly("sts:AssumeRole");
        assertThat(iam.requests).hasSize(probes().size())
            .allSatisfy(request -> {
                assertThat(request.actionNames()).hasSize(1);
                assertThat(request.policySourceArn()).isEqualTo(CALLER);
            });
    }

    @Test
    void aFullyAttachedPolicyReportsNothing() {
        assertThat(new DeployerPreflight(new FakeIam(request -> false)).simulate(CALLER, renderedPolicy())).isEmpty();
    }

    /** A caller that may not simulate gets no check, as before: the preflight is an affordance. */
    @Test
    void aCallerThatMayNotSimulateGetsNoCheck() {
        var iam = new FakeIam(request -> {
            throw (IamException) IamException.builder()
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").build()).build();
        });

        assertThat(new DeployerPreflight(iam).simulate(CALLER, renderedPolicy())).isEmpty();
    }

    /** Answers every simulation from {@code denies}: one evaluation result per request. */
    private static final class FakeIam implements IamClient {
        private final Predicate<SimulatePrincipalPolicyRequest> denies;
        final List<SimulatePrincipalPolicyRequest> requests = new ArrayList<>();

        FakeIam(Predicate<SimulatePrincipalPolicyRequest> denies) {
            this.denies = denies;
        }

        @Override
        public SimulatePrincipalPolicyResponse simulatePrincipalPolicy(SimulatePrincipalPolicyRequest request) {
            requests.add(request);
            String decision = denies.test(request) ? "implicitDeny" : "allowed";
            return SimulatePrincipalPolicyResponse.builder()
                .evaluationResults(EvaluationResult.builder()
                    .evalActionName(request.actionNames().getFirst()).evalDecision(decision).build())
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
