package pl.wsztajerowski.baas.infra;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DeployerPolicyTest {

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
    void cloudFormationAccessIsScopedToBaasStacks() {
        assertThat(statementWithSid("CloudFormation").get("Resource"))
            .isEqualTo("arn:aws:cloudformation:*:*:stack/baas-*/*");
    }

    @Test
    @SuppressWarnings("unchecked")
    void iamAccessCannotCreateArbitrarilyNamedRoles() {
        var resources = (List<String>) statementWithSid("IAM").get("Resource");

        assertThat(resources)
            .as("unscoped iam:CreateRole + iam:PutRolePolicy is an escalation path to account admin")
            .doesNotContain("*")
            .allSatisfy(arn -> assertThat(arn).matches("arn:aws:iam::\\*:(role|instance-profile)/\\*-(runner-role|operator-role)"));
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
