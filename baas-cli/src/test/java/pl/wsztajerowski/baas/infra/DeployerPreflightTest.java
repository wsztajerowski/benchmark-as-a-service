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
            "arn:aws:dynamodb:%s:%s:table/baas-%s-results"
                .formatted(InfraFixtures.REGION, InfraFixtures.ACCOUNT_ID, InfraFixtures.PREFIX));
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
