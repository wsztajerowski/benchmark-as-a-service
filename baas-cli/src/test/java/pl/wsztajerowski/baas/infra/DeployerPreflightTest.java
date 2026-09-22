package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeployerPreflightTest {

    /**
     * Before this, none of the five simulated actions was DynamoDB — a deployer running with a
     * stale attached policy passed preflight, then the real stack update failed partway on
     * dynamodb:CreateTable and rolled back. dynamodb:CreateTable is unconditioned and
     * resource-scoped, exactly the shape simulateCriticalActions was built for, so it belongs in
     * the same map as cloudformation:CreateStack, s3:CreateBucket, ssm:PutParameter, iam:GetRole
     * and iam:CreateRole.
     */
    @Test
    void simulatesDynamoDbTableCreationSoAStalePolicyFailsBeforePreflightPasses() {
        var actions = DeployerPreflight.criticalActionsToResources(
            InfraFixtures.ACCOUNT_ID, InfraFixtures.REGION, InfraFixtures.PREFIX);

        assertThat(actions).containsEntry("dynamodb:CreateTable",
            "arn:aws:dynamodb:%s:%s:table/%s-results"
                .formatted(InfraFixtures.REGION, InfraFixtures.ACCOUNT_ID, InfraFixtures.PREFIX));
    }

    /**
     * The same lesson arriving from a new direction. Deploying a core stack with federation
     * parameters rewrites OperatorRole's trust policy, and {@code iam:UpdateAssumeRolePolicy} is
     * newer than any deployer policy attached before it existed — so the stale-policy case is the
     * expected one, not the exotic one. Verified live against this account before the probe was
     * added: {@code SimulatePrincipalPolicy} returned {@code implicitDeny} for it while
     * {@code iam:CreateRole} returned {@code allowed}, meaning preflight would have passed and the
     * update would have rolled back partway.
     */
    @Test
    void simulatesTheTrustPolicyEditSoAStalePolicyFailsBeforeTheStackRollsBack() {
        var actions = DeployerPreflight.criticalActionsToResources(
            InfraFixtures.ACCOUNT_ID, InfraFixtures.REGION, InfraFixtures.PREFIX);

        assertThat(actions).containsEntry("iam:UpdateAssumeRolePolicy",
            "arn:aws:iam::%s:role/%s-role-operator"
                .formatted(InfraFixtures.ACCOUNT_ID, InfraFixtures.PREFIX));
    }

    /**
     * The half that was missed, and it cost a stuck stack. Federating changes OperatorRole through
     * two IAM APIs — the trust policy above, and {@code iam:UpdateRole} for MaxSessionDuration.
     * Probing only the first let preflight pass, the update fail partway on the second, and then
     * the <em>rollback</em> fail on it as well (unwinding MaxSessionDuration needs the same
     * action), leaving {@code baas-3q7i7s65} in UPDATE_ROLLBACK_FAILED — a state that needs
     * ContinueUpdateRollback and a human, which is exactly what preflight exists to avoid.
     */
    @Test
    void simulatesTheMaxSessionDurationChangeToo() {
        var actions = DeployerPreflight.criticalActionsToResources(
            InfraFixtures.ACCOUNT_ID, InfraFixtures.REGION, InfraFixtures.PREFIX);

        assertThat(actions).containsEntry("iam:UpdateRole",
            "arn:aws:iam::%s:role/%s-role-operator"
                .formatted(InfraFixtures.ACCOUNT_ID, InfraFixtures.PREFIX));
    }

    /**
     * Stated as a rule rather than as two cases: every IAM action the preflight probes must also
     * be granted by the policy it prints when the probe fails. A probe for an action the rendered
     * policy does not grant would tell the user to attach a policy that still cannot deploy.
     */
    @Test
    void everyProbedIamActionIsGrantedByTheRenderedPolicy() {
        var probed = DeployerPreflight.criticalActionsToResources(
            InfraFixtures.ACCOUNT_ID, InfraFixtures.REGION, InfraFixtures.PREFIX).keySet();

        assertThat(probed.stream().filter(action -> action.startsWith("iam:")).toList())
            .isNotEmpty()
            .allSatisfy(action -> assertThat(
                InfraFixtures.grants(InfraFixtures.deployerPolicy(), action))
                .as("preflight probes %s but the rendered policy does not grant it", action)
                .isTrue());
    }

    @Test
    void simulatesTheOtherFourCriticalActionsToo() {
        var actions = DeployerPreflight.criticalActionsToResources(
            InfraFixtures.ACCOUNT_ID, InfraFixtures.REGION, InfraFixtures.PREFIX);

        assertThat(actions).containsKeys(
            "cloudformation:CreateStack", "s3:CreateBucket", "ssm:PutParameter",
            "iam:GetRole", "iam:CreateRole");
    }

    /**
     * Since the cutover no command writes {@code /<prefix>/mongo/connection-string}, so probing
     * it proved nothing about a policy the deployer actually needs. The runner AMI pointer is the
     * SSM write that remains — and §14 removes the Mongo grant from the deployer policy, which
     * would have turned the old probe into a preflight failure for a permission nothing uses.
     */
    @Test
    void simulatesTheSsmWriteTheDeployerStillPerforms() {
        var actions = DeployerPreflight.criticalActionsToResources(
            InfraFixtures.ACCOUNT_ID, InfraFixtures.REGION, InfraFixtures.PREFIX);

        assertThat(actions).containsEntry("ssm:PutParameter",
            "arn:aws:ssm:%s:%s:parameter/%s/runner/ami-id"
                .formatted(InfraFixtures.REGION, InfraFixtures.ACCOUNT_ID, InfraFixtures.PREFIX));
    }
}
