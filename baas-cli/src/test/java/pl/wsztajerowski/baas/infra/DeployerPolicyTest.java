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
        assertThat(InfraFixtures.grants(InfraFixtures.deployerPolicy(), requiredAction))
            .as("%s is not permitted by the rendered policy", requiredAction)
            .isTrue();
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
     * {@code ValidateTemplate} operates on a template body rather than a stack, so IAM offers it no
     * resource-level scoping — it can only be granted on {@code "*"}. It therefore gets its own
     * statement: folding it into the stack-scoped one above would silently widen that grant from a
     * single stack to every stack in the account, which is the opposite of what it looks like.
     */
    @Test
    void validateTemplateIsGrantedWithoutWideningTheStackScopedGrant() {
        var validate = statementWithSid("CloudFormationValidateTemplate");

        assertThat(validate.get("Action")).isEqualTo("cloudformation:ValidateTemplate");
        assertThat(validate.get("Resource"))
            .as("AWS supports no resource-level permission for this action")
            .isEqualTo("*");

        assertThat(statementWithSid("CloudFormation").get("Action"))
            .as("the stack-scoped statement must not be the one carrying it")
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
            .doesNotContain("cloudformation:ValidateTemplate");
    }

    /**
     * The action reads no account state — it parses a template body and echoes back its parameters
     * — so the wildcard resource above grants no reach. Pinning the region keeps it consistent
     * with the rest of the policy regardless.
     */
    @Test
    void validateTemplateIsPinnedToTheCallersRegion() {
        @SuppressWarnings("unchecked")
        var condition = (Map<String, Object>)
            statementWithSid("CloudFormationValidateTemplate").get("Condition");
        @SuppressWarnings("unchecked")
        var stringEquals = (Map<String, Object>) condition.get("StringEquals");

        assertThat(stringEquals).containsEntry("aws:RequestedRegion", InfraFixtures.REGION);
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

    @ParameterizedTest
    @ValueSource(strings = {
        // `baas admin build-image` registers the recipe, then runs the pipeline
        "imagebuilder:CreateComponent",
        "imagebuilder:CreateImageRecipe",
        "imagebuilder:CreateInfrastructureConfiguration",
        "imagebuilder:CreateDistributionConfiguration",
        "imagebuilder:CreateImagePipeline",
        "imagebuilder:StartImagePipelineExecution",
        // the version preflight reads back the registered component to compare its content
        "imagebuilder:GetComponent",
        // polling a build to completion, and reading the AMI it produced
        "imagebuilder:GetImage",
        "imagebuilder:TagResource",
        // retiring the AMI the build replaced
        "ec2:DeregisterImage",
        "ec2:DeleteSnapshot",
        "ec2:DescribeImages",
        "ec2:DescribeSnapshots",
        "ec2:CreateTags"
    })
    void grantsActionNeededByBuildImage(String requiredAction) {
        assertThat(InfraFixtures.grants(InfraFixtures.deployerPolicy(), requiredAction))
            .as("%s is not permitted by the rendered policy", requiredAction)
            .isTrue();
    }

    /**
     * {@code imagebuilder:CreateImage} builds an image directly, bypassing the pipeline the stack
     * declares — and therefore bypassing the recipe version that every result is tagged with.
     *
     * <p>This is why the {@code Create} actions stay enumerated while the other verbs are
     * wildcarded: an {@code imagebuilder:Create*} would quietly pull this one back in.
     */
    @Test
    void cannotBuildAnImageOutsideThePipeline() {
        assertThat(InfraFixtures.grants(InfraFixtures.deployerPolicy(), "imagebuilder:CreateImage"))
            .isFalse();
        assertThat(InfraFixtures.actions(InfraFixtures.deployerPolicy()))
            .as("a Create wildcard would grant CreateImage without ever naming it")
            .doesNotContain("imagebuilder:Create*");
    }

    /**
     * {@code s3:Put*} would grant {@code PutObject}: the deployer provisions the bucket, it does not
     * write results into it. The reads are wildcarded because CloudFormation's bucket read handler
     * needs about twenty of them, and naming each one is what pushed this document over IAM's
     * inline-policy limit.
     */
    @Test
    void deployerProvisionsTheBucketWithoutWritingObjectsIntoIt() {
        var policy = InfraFixtures.deployerPolicy();

        assertThat(InfraFixtures.grants(policy, "s3:GetBucketVersioning")).isTrue();
        assertThat(InfraFixtures.grants(policy, "s3:PutLifecycleConfiguration")).isTrue();
        assertThat(InfraFixtures.grants(policy, "s3:PutObject"))
            .as("uploading benchmark JARs is the operator's job, on operator credentials")
            .isFalse();
    }

    /**
     * Every Image Builder permission that <em>acts</em> — creates, deletes, updates, tags, or
     * starts a build — stays pinned to this caller's own resources. The lone exception is
     * {@code ListComponents}, which IAM can only authorise against the whole collection; it
     * reveals component names and grants no way to touch one.
     */
    @Test
    @SuppressWarnings("unchecked")
    void imageBuilderStatementsThatActStayPrefixScoped() {
        var statements = (List<Map<String, Object>>) InfraFixtures.deployerPolicy().get("Statement");

        var actingResources = statements.stream()
            .filter(statement -> String.valueOf(statement.get("Action")).contains("imagebuilder:"))
            .filter(statement -> !"ImageBuilderRead".equals(statement.get("Sid")))
            .flatMap(statement -> {
                Object resource = statement.get("Resource");
                return resource instanceof List<?> list
                    ? list.stream().map(String::valueOf)
                    : java.util.stream.Stream.of(String.valueOf(resource));
            })
            .toList();

        assertThat(actingResources)
            .as("Resource:* here would let one deployer start another's pipeline, and every image "
                + "is the measurement environment for someone's results")
            .isNotEmpty()
            .doesNotContain("*")
            .allMatch(arn -> arn.contains(InfraFixtures.PREFIX + "-runner"));

        assertThat((List<String>) statementWithSid("ImageBuilderRead").get("Action"))
            .as("the exception must stay read-only — nothing that acts belongs on Resource:*")
            .allMatch(action -> action.startsWith("imagebuilder:Get")
                || action.startsWith("imagebuilder:List"));
    }

    /**
     * A widened PassRole is the escalation the credential split exists to prevent: the build
     * instance profile is the only role the deployer needs to hand to Image Builder.
     */
    @Test
    @SuppressWarnings("unchecked")
    void passRoleReachesOnlyTheTwoRolesTheStackCreates() {
        var passRole = (List<String>) statementWithSid("PassRolesToServices").get("Resource");

        assertThat(passRole)
            .as("EC2 gets the runner role, Image Builder gets the build role, and nothing else is "
                + "passable — a widened PassRole is the escalation the credential split prevents")
            .containsExactlyInAnyOrder(
                "arn:aws:iam::%s:role/%s-runner-role"
                    .formatted(InfraFixtures.ACCOUNT_ID, InfraFixtures.PREFIX),
                "arn:aws:iam::%s:role/%s-image-build-role"
                    .formatted(InfraFixtures.ACCOUNT_ID, InfraFixtures.PREFIX));

        assertThat(passRole)
            .as("notably not the operator role, which is assumed rather than passed")
            .noneMatch(arn -> arn.contains("operator"));
    }

    @Test
    void publishesTheAmiPointerForExactlyOneCaller() {
        assertThat(statementWithSid("SsmRunnerAmiPointer").get("Resource"))
            .as("a wildcard here would let one deployer repoint another's runners at their image")
            .isEqualTo("arn:aws:ssm:%s:%s:parameter/%s/runner/ami-id"
                .formatted(InfraFixtures.REGION, InfraFixtures.ACCOUNT_ID, InfraFixtures.PREFIX));
    }

    /**
     * IAM's tightest realistic ceiling for this document is the 2048-character inline-user limit,
     * which it has never fitted; the binding one in practice is 5120 for an inline policy on a
     * group or user, <em>shared across every inline policy on that principal</em>. A managed policy
     * gets 6144 to itself.
     *
     * <p>Held at 4096 rather than 5120 so the deployer leaves room for whatever else is attached to
     * the same principal — this document is not the only thing competing for that budget, and the
     * failure it prevents surfaces in the IAM console, nowhere near the edit that caused it.
     */
    @Test
    void renderedPolicyLeavesRoomInAnInlinePolicyBudget() {
        String rendered = new DeployerPolicyRenderer()
            .render(InfraFixtures.ACCOUNT_ID, InfraFixtures.REGION, InfraFixtures.PREFIX);
        int counted = rendered.replaceAll("\\s", "").length();

        assertThat(counted)
            .as("collapse a verb class to a wildcard, or drop an unused action, before adding more")
            .isLessThanOrEqualTo(4096);
    }

    /**
     * Creating the first ImagePipeline in an account makes Image Builder provision its
     * service-linked role, and the create fails with AccessDenied without this. It appears in no
     * SDK call the CLI makes and in no CloudFormation resource schema — a real deploy is the only
     * thing that finds it, which is how it was found.
     */
    @Test
    @SuppressWarnings("unchecked")
    void canLetImageBuilderCreateItsServiceLinkedRole() {
        var statement = statementWithSid("ImageBuilderServiceLinkedRole");

        assertThat(statement.get("Action")).isEqualTo("iam:CreateServiceLinkedRole");
        assertThat((String) statement.get("Resource"))
            .as("scoped to Image Builder's own service-role path, not roles generally")
            .contains("role/aws-service-role/imagebuilder.amazonaws.com/");

        var condition = (Map<String, Object>) statement.get("Condition");
        var stringEquals = (Map<String, Object>) condition.get("StringEquals");
        assertThat(stringEquals)
            .as("without this the grant would extend to every service-linked role in the account")
            .containsEntry("iam:AWSServiceName", "imagebuilder.amazonaws.com");
    }

    /**
     * Wildcards here buy size, so each one has to stay inside a verb class that is safe on
     * prefix-scoped resources. Read verbs qualify; {@code Create} does not, because
     * {@code CreateImage} is deliberately excluded.
     */
    @Test
    void imageBuilderWildcardsCoverTheCallsTheCliAndCloudFormationMake() {
        var policy = InfraFixtures.deployerPolicy();

        assertThat(InfraFixtures.grants(policy, "imagebuilder:ListComponents")).isTrue();
        assertThat(InfraFixtures.grants(policy, "imagebuilder:ListComponentBuildVersions")).isTrue();
        assertThat(InfraFixtures.grants(policy, "imagebuilder:GetComponent")).isTrue();
        assertThat(InfraFixtures.grants(policy, "imagebuilder:DeleteImageRecipe")).isTrue();
        assertThat(InfraFixtures.grants(policy, "imagebuilder:UpdateImagePipeline")).isTrue();
    }

    /**
     * Image Builder authorises its read operations against the collection — {@code component/*}
     * — even when the call names one specific ARN, so a prefix-scoped resource can never satisfy
     * them and the version preflight dies with AccessDenied. Writes are authorised against the
     * named resource and stay scoped.
     *
     * <p>Established against the live account, not from documentation: {@code GetComponent} and
     * {@code ListComponentBuildVersions} were denied under the scoped grant, while
     * {@code GetImagePipeline} on an equally scoped ARN succeeded.
     */
    @Test
    @SuppressWarnings("unchecked")
    void imageBuilderReadsCannotBePrefixScoped() {
        var read = statementWithSid("ImageBuilderRead");

        assertThat((List<String>) read.get("Action"))
            .containsExactlyInAnyOrder("imagebuilder:Get*", "imagebuilder:List*");
        assertThat(read.get("Resource")).isEqualTo("*");

        var condition = (Map<String, Object>) read.get("Condition");
        assertThat((Map<String, Object>) condition.get("StringEquals"))
            .as("read-only, but still confined to the region this deployer works in")
            .containsEntry("aws:RequestedRegion", InfraFixtures.REGION);

        assertThat((List<String>) statementWithSid("ImageBuilder").get("Action"))
            .as("moving a read back into the scoped block reads as granted and behaves as denied")
            .noneMatch(action -> action.startsWith("imagebuilder:Get")
                || action.startsWith("imagebuilder:List"));
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
