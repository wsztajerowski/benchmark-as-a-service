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
        var iam = new FakeIam(action -> action.equals("sts:AssumeRole"));

        var denied = new DeployerPreflight(iam).simulate(CALLER, renderedPolicy());

        assertThat(denied).containsExactly("sts:AssumeRole");
        assertThat(iam.requests).allSatisfy(request -> assertThat(request.policySourceArn()).isEqualTo(CALLER));
        assertThat(iam.requests.stream().flatMap(request -> request.actionNames().stream()).toList())
            .as("every probed action is asked about exactly once")
            .containsExactlyInAnyOrderElementsOf(probes().stream().map(DeployerPreflight.Probe::action).toList());
    }

    /**
     * A statement goes in one call, so 56 actions take a handful of calls. IAM throttles this API
     * hard: one call per action took ~26 s live.
     */
    @Test
    void eachStatementTakesAtMostTwoCalls() {
        var iam = new FakeIam(action -> false);

        new DeployerPreflight(iam).simulate(CALLER, renderedPolicy());

        assertThat(iam.refused).as("wildcard and plain actions are never mixed").isZero();
        assertThat(iam.requests).hasSize(13);
    }

    /**
     * IAM refuses one call mixing actions that "require different authorization information"
     * (iam:Get* beside iam:CreateRole, found live): the call is split until IAM accepts it, and a
     * denial inside a split part is still found.
     */
    @Test
    void aRefusedMixIsSplitAndStillJudgedPerAction() {
        var iam = new FakeIam(action -> action.equals("iam:UpdateRole"));
        iam.refuseMixesOf = "iam:";

        var denied = new DeployerPreflight(iam).simulate(CALLER, renderedPolicy());

        assertThat(denied).containsExactly("iam:UpdateRole");
        assertThat(iam.refused).isPositive();
    }

    @Test
    void aFullyAttachedPolicyReportsNothing() {
        assertThat(new DeployerPreflight(new FakeIam(action -> false)).simulate(CALLER, renderedPolicy())).isEmpty();
    }

    /** A caller that may not simulate gets no check, as before: the preflight is an affordance. */
    @Test
    void aCallerThatMayNotSimulateGetsNoCheck() {
        var iam = new FakeIam(action -> false);
        iam.failure = "AccessDenied";

        assertThat(new DeployerPreflight(iam).simulate(CALLER, renderedPolicy())).isEmpty();
    }

    /** IAM throttles SimulatePrincipalPolicy hard: a throttled call waits and asks again. */
    @Test
    void aThrottledCallIsAskedAgainRatherThanTurningTheCheckOff() {
        var iam = new FakeIam(action -> action.equals("sts:AssumeRole"));
        iam.throttleOnce = "sts:AssumeRole";

        assertThat(new DeployerPreflight(iam).simulate(CALLER, renderedPolicy())).containsExactly("sts:AssumeRole");
    }

    /**
     * Answers each action of a simulation from {@code denies}. Like IAM, it refuses a call mixing a
     * wildcard action with a plain one (the "different authorization information" case).
     */
    private static final class FakeIam implements IamClient {
        private final Predicate<String> denies;
        final List<SimulatePrincipalPolicyRequest> requests = new ArrayList<>();
        /** Refuses any multi-action call for actions of this prefix: a mix IAM refuses on some other line. */
        String refuseMixesOf;
        int refused;
        String failure;
        String throttleOnce;

        FakeIam(Predicate<String> denies) {
            this.denies = denies;
        }

        @Override
        public SimulatePrincipalPolicyResponse simulatePrincipalPolicy(SimulatePrincipalPolicyRequest request) {
            if (failure != null) {
                throw error(failure);
            }
            if (throttleOnce != null && request.actionNames().contains(throttleOnce)) {
                throttleOnce = null;
                throw error("Throttling");
            }
            boolean anyWildcard = request.actionNames().stream().anyMatch(action -> action.contains("*"));
            boolean anyPlain = request.actionNames().stream().anyMatch(action -> !action.contains("*"));
            boolean refusedHere = refuseMixesOf != null && request.actionNames().size() > 1
                && request.actionNames().getFirst().startsWith(refuseMixesOf);
            if ((anyWildcard && anyPlain) || refusedHere) {
                refused++;
                throw error("InvalidInput");
            }
            requests.add(request);
            return SimulatePrincipalPolicyResponse.builder()
                .evaluationResults(request.actionNames().stream()
                    .map(action -> EvaluationResult.builder().evalActionName(action)
                        .evalDecision(denies.test(action) ? "implicitDeny" : "allowed").build())
                    .toList())
                .build();
        }

        private static IamException error(String code) {
            return (IamException) IamException.builder().statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build()).build();
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
