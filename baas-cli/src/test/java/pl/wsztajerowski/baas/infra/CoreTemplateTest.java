package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CoreTemplateTest {

    private final Map<String, Object> template = InfraFixtures.coreTemplate();

    @Test
    void bucketIsNamedFromTheResourcePrefix() {
        Map<String, Object> bucket = InfraFixtures.properties(template, "S3MainBucket");

        assertThat(bucket.get("BucketName")).isEqualTo("baas-${ResourceNamePrefix}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void runnerCanReachMongoAtlasOnItsStandardPort() {
        var egress = (List<Map<String, Object>>)
            InfraFixtures.properties(template, "RunnerSecurityGroup").get("SecurityGroupEgress");

        assertThat(egress)
            .as("MongoDB Atlas listens on 27017 — without it every run fails at the database write")
            .anySatisfy(rule -> {
                assertThat(rule.get("IpProtocol")).isEqualTo("tcp");
                assertThat(rule.get("FromPort")).isEqualTo(27017);
                assertThat(rule.get("ToPort")).isEqualTo(27017);
            });
    }

    @Test
    @SuppressWarnings("unchecked")
    void operatorCanReadItsOwnStackOutputs() {
        var policies = (List<Map<String, Object>>)
            InfraFixtures.properties(template, "OperatorRole").get("Policies");

        var actions = policies.stream()
            .map(policy -> (Map<String, Object>) policy.get("PolicyDocument"))
            .flatMap(document -> InfraFixtures.actions(document).stream())
            .toList();

        assertThat(actions)
            .as("without this an operator cannot populate config.yaml without hand-copying it")
            .contains("cloudformation:DescribeStacks");
    }

    @Test
    @SuppressWarnings("unchecked")
    void bucketGrowthIsBounded() {
        var lifecycle = (Map<String, Object>)
            InfraFixtures.properties(template, "S3MainBucket").get("LifecycleConfiguration");

        assertThat(lifecycle).as("versioning without lifecycle rules grows without bound").isNotNull();

        var rules = (List<Map<String, Object>>) lifecycle.get("Rules");
        assertThat(rules).anySatisfy(rule ->
            assertThat(rule).containsKey("NoncurrentVersionExpiration"));
        assertThat(rules).anySatisfy(rule ->
            assertThat(rule).containsKey("AbortIncompleteMultipartUpload"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void bucketFollowsTheDashedTagConvention() {
        var tags = (List<Map<String, Object>>)
            InfraFixtures.properties(template, "S3MainBucket").get("Tags");

        assertThat(tags).extracting(tag -> tag.get("Key")).contains("baas-role");
        assertThat(tags).extracting(tag -> tag.get("Key")).doesNotContain("role");
    }

    @Test
    @SuppressWarnings("unchecked")
    void operatorCannotLaunchArbitrarilyLargeInstances() {
        var runInstances = operatorStatementsFor("ec2:RunInstances");

        assertThat(runInstances)
            .as("RunInstances is authorized once per resource in the request, so the instance-type "
                + "constraint and the supporting-resource grant have to be separate statements")
            .hasSize(2);

        var instanceLeg = runInstances.stream()
            .filter(statement -> String.valueOf(statement.get("Resource")).contains(":instance/"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No instance-scoped ec2:RunInstances statement"));

        var instanceCondition = (Map<String, Object>) instanceLeg.get("Condition");
        assertThat((Map<String, Object>) instanceCondition.get("StringLike"))
            .as("an unconstrained RunInstances turns a typo into a four-figure bill")
            .containsKey("ec2:InstanceType");

        var supportingLeg = runInstances.stream()
            .filter(statement -> statement != instanceLeg)
            .findFirst()
            .orElseThrow();

        assertThat((Map<String, Object>) supportingLeg.get("Condition"))
            .as("ec2:InstanceType is absent from the image/subnet/volume request context, so a "
                + "StringLike on it here evaluates false and denies the whole RunInstances call")
            .doesNotContainKey("StringLike");

        assertThat(String.valueOf(supportingLeg.get("Resource")))
            .as("every resource EC2 authorizes RunInstances against needs its own grant")
            .contains(":image/", ":subnet/", ":security-group/", ":network-interface/", ":volume/");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> operatorStatementsFor(String action) {
        var policies = (List<Map<String, Object>>)
            InfraFixtures.properties(template, "OperatorRole").get("Policies");

        return policies.stream()
            .map(policy -> (Map<String, Object>) policy.get("PolicyDocument"))
            .flatMap(document -> ((List<Map<String, Object>>) document.get("Statement")).stream())
            .filter(statement -> String.valueOf(statement.get("Action")).contains(action))
            .toList();
    }

    @Test
    @SuppressWarnings("unchecked")
    void stackNeverPerformsAnImageBuild() {
        var resources = (Map<String, Object>) template.get("Resources");

        assertThat(resources.values())
            .as("AWS::ImageBuilder::Image builds during stack operations — it would add ~15 minutes "
                + "to every `baas admin setup`, including ones that changed nothing about the image")
            .noneSatisfy(resource ->
                assertThat(((Map<String, Object>) resource).get("Type"))
                    .isEqualTo("AWS::ImageBuilder::Image"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void imageRecipeVolumeMatchesTheRunnerVolume() {
        var mappings = (List<Map<String, Object>>)
            InfraFixtures.properties(template, "RunnerImageRecipe").get("BlockDeviceMappings");

        assertThat(mappings)
            .as("8 GB is exhausted by profiling artifacts, and the image caps what the runner gets")
            .anySatisfy(mapping -> {
                var ebs = (Map<String, Object>) mapping.get("Ebs");
                assertThat(ebs).containsEntry("VolumeSize", 30).containsEntry("VolumeType", "gp3");
            });
    }

    @Test
    void imageRecipeIsPinnedToAnExactParentAmi() {
        assertThat(InfraFixtures.properties(template, "RunnerImageRecipe").get("ParentImage"))
            .as("the parent has to arrive as the rendered runner-image.yaml pin, not a selector")
            .isEqualTo("RunnerParentAmiId");

        var parameters = (Map<String, Object>) template.get("Parameters");
        var parentImage = (Map<String, Object>) parameters.get("RunnerParentAmiId");
        assertThat(parentImage.get("AllowedPattern"))
            .as("an x.x.x semantic-version selector would re-base the environment silently")
            .isEqualTo("^ami-[0-9a-f]+$");
    }

    /**
     * Every Image Builder resource exposes a distinct {@code Arn} attribute, which is the shape
     * where {@code Ref} is liable to return the name instead. These properties reject a name, and
     * the pipeline ARN is handed straight to {@code StartImagePipelineExecution} — so the wiring
     * asks for the ARN explicitly rather than relying on what {@code Ref} happens to yield.
     */
    @Test
    @SuppressWarnings("unchecked")
    void imageBuilderWiringAsksForArnsExplicitly() {
        var pipeline = InfraFixtures.properties(template, "RunnerImagePipeline");
        assertThat(pipeline)
            .containsEntry("ImageRecipeArn", "RunnerImageRecipe.Arn")
            .containsEntry("InfrastructureConfigurationArn", "RunnerImageInfrastructure.Arn")
            .containsEntry("DistributionConfigurationArn", "RunnerImageDistribution.Arn");

        var components = (List<Map<String, Object>>)
            InfraFixtures.properties(template, "RunnerImageRecipe").get("Components");
        assertThat(components)
            .singleElement()
            .satisfies(component ->
                assertThat(component).containsEntry("ComponentArn", "RunnerImageComponent.Arn"));

        var outputs = (Map<String, Object>) template.get("Outputs");
        assertThat((Map<String, Object>) outputs.get("RunnerImagePipelineArn"))
            .as("baas admin build-image passes this value verbatim to StartImagePipelineExecution")
            .containsEntry("Value", "RunnerImagePipeline.Arn");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildInstanceRoleCannotLaunchOrEscalate() {
        var policies = (List<Map<String, Object>>)
            InfraFixtures.properties(template, "ImageBuildRole").get("Policies");

        var actions = policies.stream()
            .map(policy -> (Map<String, Object>) policy.get("PolicyDocument"))
            .flatMap(document -> InfraFixtures.actions(document).stream())
            .toList();

        assertThat(actions)
            .as("the build host installs packages; anything beyond writing its own logs is reach "
                + "it has no use for")
            .containsExactly("s3:PutObject")
            .noneMatch(action -> action.startsWith("dynamodb:"))
            .noneMatch(action -> action.startsWith("iam:"))
            .doesNotContain("ec2:RunInstances");

        assertThat(InfraFixtures.resources(
            (Map<String, Object>) policies.getFirst().get("PolicyDocument")))
            .as("build logs only — not the results the bucket also holds")
            .containsExactly("${S3MainBucket.Arn}/image-builds/*");
    }

    @Test
    @SuppressWarnings("unchecked")
    void exactlyOneRunnerAmiPointerIsProvidedFor() {
        var pointers = InfraFixtures.properties(template, "OperatorRole").get("Policies");
        var statements = ((List<Map<String, Object>>) pointers).stream()
            .map(policy -> (Map<String, Object>) policy.get("PolicyDocument"))
            .flatMap(document -> InfraFixtures.resources(document).stream())
            .filter(resource -> resource.contains("/runner/ami-id"))
            .toList();

        assertThat(statements)
            .as("named slots or a second pointer would let two runs disagree about which image "
                + "'the' image is")
            .containsExactly(
                "arn:${AWS::Partition}:ssm:${AWS::Region}:${AWS::AccountId}:parameter/${ResourceNamePrefix}/runner/ami-id");

        var outputs = (Map<String, Object>) template.get("Outputs");
        assertThat(outputs).containsKey("RunnerAmiParameterName");
    }

    @Test
    void workingBucketSurvivesStackDeletion() {
        var bucket = InfraFixtures.resource(template, "S3MainBucket");

        assertThat(bucket)
            .as("teardown promises the bucket is retained — that must be declared, not a side effect of a failing delete")
            .containsEntry("DeletionPolicy", "Retain")
            .containsEntry("UpdateReplacePolicy", "Retain");
    }
}
