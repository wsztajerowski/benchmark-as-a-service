package pl.wsztajerowski.baas.infra;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DeployerPolicyTest {

    /**
     * Found by a real deploy, not by reading docs: iam:AddRoleToInstanceProfile additionally
     * requires iam:PassRole on the role being added, so RunnerInstanceProfile fails to create
     * without it even though every Create/Add action is granted.
     */
    @Test
    void canPassTheRunnerRoleIntoItsInstanceProfile() {
        assertThat(InfraFixtures.actions(InfraFixtures.deployerPolicy()))
            .as("AddRoleToInstanceProfile is not sufficient on its own")
            .contains("iam:PassRole");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // baas admin setup --mongo-uri writes the SecureString parameter
        "ssm:PutParameter",
        // baas admin teardown --delete-bucket empties the bucket, then CloudFormation removes it
        "s3:ListBucket",
        "s3:ListBucketVersions",
        "s3:DeleteObject",
        "s3:DeleteObjectVersion",
        "s3:DeleteBucket",
        // CloudFormation reads an IAM role's inline policies back after creating it
        "iam:GetRolePolicy",
        "iam:ListRolePolicies",
        "iam:ListAttachedRolePolicies"
    })
    void grantsActionNeededBySetupOrTeardown(String requiredAction) {
        assertThat(InfraFixtures.actions(InfraFixtures.deployerPolicy()))
            .contains(requiredAction);
    }

    @Test
    void holdsNoCiStackPermissions() {
        assertThat(InfraFixtures.actions(InfraFixtures.deployerPolicy()))
            .as("the core/CI split exists so the local identity never touches GitHub OIDC trust")
            .noneMatch(action -> action.endsWith("OpenIDConnectProvider"))
            .doesNotContain("iam:UpdateAssumeRolePolicy");
    }

    @Test
    void cloudFormationAccessIsScopedToOneStack() {
        assertThat(statementWithSid("CloudFormation").get("Resource"))
            .isEqualTo("arn:aws:cloudformation:%s:%s:stack/baas-%s/*"
                .formatted(InfraFixtures.REGION, InfraFixtures.ACCOUNT_ID, InfraFixtures.PREFIX));
    }

    /**
     * Deliberately not asserted: that a deployer cannot escalate. It can — {@code iam:CreateRole}
     * writes the trust policy, so it can recreate {@code <prefix>-operator-role} trusting itself
     * with an inline {@code Action:*}. That is an accepted risk for an internal tool whose
     * deployer is a trusted developer; see CLAUDE.md. What the per-caller scoping below *does*
     * buy is that one deployer cannot reach another's stack, bucket or parameter.
     */
    @Test
    void renderedPolicyNamesExactlyOneCallersResources() {
        // A trailing /* (S3 objects) and the simulate statement's account-wide principal are
        // legitimate; a *prefix* wildcard is what would cross into another deployer's resources.
        assertThat(InfraFixtures.resources(InfraFixtures.deployerPolicy()))
            .as("a prefix wildcard lets one deployer reach another's roles, parameters or bucket")
            .noneMatch(arn -> arn.contains("baas-*"))
            .noneMatch(arn -> arn.contains("*-runner-role"))
            .noneMatch(arn -> arn.contains("*-operator-role"))
            .noneMatch(arn -> arn.contains("parameter/*/"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> statementWithSid(String sid) {
        var statements = (List<Map<String, Object>>) InfraFixtures.deployerPolicy().get("Statement");
        return statements.stream()
            .filter(statement -> sid.equals(statement.get("Sid")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No statement with Sid " + sid));
    }
}
