package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.model.MeasurementItemMapper;
import pl.wsztajerowski.baas.model.ResultKeys;

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

/**
     * Inverted, not deleted. This assertion previously pinned 27017's PRESENCE, because Atlas does
     * not serve clients on 443 and omitting the rule made every run fail at the database write.
     * Measurements go to DynamoDB over a gateway endpoint now, so the rule grants egress nothing
     * uses — and a security group rule nobody can explain is one somebody restores. Keeping the
     * test as a negative is what makes its removal deliberate rather than reversible by accident.
     */
    @Test
    @SuppressWarnings("unchecked")
    void runnerHasNoEgressToMongoAtlasAnyMore() {
        var egress = (List<Map<String, Object>>)
            InfraFixtures.properties(template, "RunnerSecurityGroup").get("SecurityGroupEgress");

        assertThat(egress)
            .as("nothing connects to Atlas since the DynamoDB cutover; the runner reaches the "
                + "table over the gateway endpoint, so 27017 grants egress nothing uses")
            .noneSatisfy(rule -> assertThat(rule.get("FromPort")).isEqualTo(27017));
        assertThat(egress)
            .as("443 and 80 are still needed: GitHub Releases, S3, the AWS APIs")
            .hasSize(2);
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

    @Test
    void theResultsTableIsRetainedOnBothDeleteAndReplace() {
        var table = InfraFixtures.resource(template, "ResultsTable");

        assertThat(table)
            .as("benchmark history outlives the stack, same as the bucket — losing it to a stray "
                + "teardown or a replacement update is not recoverable")
            .containsEntry("DeletionPolicy", "Retain")
            .containsEntry("UpdateReplacePolicy", "Retain");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theResultsTableIsOnDemandWithStringKeys() {
        var properties = InfraFixtures.properties(template, "ResultsTable");

        assertThat(properties.get("BillingMode")).isEqualTo("PAY_PER_REQUEST");

        var keySchema = (List<Map<String, Object>>) properties.get("KeySchema");
        assertThat(keySchema).hasSize(2);
        assertThat(keySchema.get(0)).containsEntry("AttributeName", MeasurementItemMapper.PK).containsEntry("KeyType", "HASH");
        assertThat(keySchema.get(1)).containsEntry("AttributeName", MeasurementItemMapper.SK).containsEntry("KeyType", "RANGE");

        var attributeDefinitions = (List<Map<String, Object>>) properties.get("AttributeDefinitions");
        assertThat(attributeDefinitions)
            .as("all four key attributes — table keys and GSI keys alike — must be pinned to S")
            .extracting(attribute -> attribute.get("AttributeName"))
            .containsExactlyInAnyOrder(MeasurementItemMapper.PK, MeasurementItemMapper.SK,
                MeasurementItemMapper.GSI1PK, MeasurementItemMapper.GSI1SK);
        assertThat(attributeDefinitions)
            .extracting(attribute -> attribute.get("AttributeType"))
            .containsOnly("S");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theResultsTableHasExactlyOneIndexKeyedOnRequestId() {
        var properties = InfraFixtures.properties(template, "ResultsTable");
        var indexes = (List<Map<String, Object>>) properties.get("GlobalSecondaryIndexes");

        assertThat(indexes).hasSize(1);
        assertThat(indexes.get(0).get("IndexName")).isEqualTo(ResultKeys.REQUEST_ID_INDEX_NAME);

        var keySchema = (List<Map<String, Object>>) indexes.get(0).get("KeySchema");
        assertThat(keySchema).hasSize(2);
        assertThat(keySchema.get(0)).containsEntry("AttributeName", MeasurementItemMapper.GSI1PK).containsEntry("KeyType", "HASH");
        assertThat(keySchema.get(1)).containsEntry("AttributeName", MeasurementItemMapper.GSI1SK).containsEntry("KeyType", "RANGE");

        var projection = (Map<String, Object>) indexes.get(0).get("Projection");
        assertThat(projection.get("ProjectionType")).isEqualTo("ALL");
    }

    @Test
    void theResultsTableHasNoTimeToLive() {
        assertThat(InfraFixtures.properties(template, "ResultsTable"))
            .doesNotContainKey("TimeToLiveSpecification");
    }

    /** A gateway endpoint is free; an interface endpoint bills hourly per AZ. */
    @Test
    void dynamoDbIsReachedThroughAGatewayEndpointNotAnInterfaceEndpoint() {
        var resource = InfraFixtures.resource(template, "DynamoDbGatewayEndpoint");
        var properties = InfraFixtures.properties(template, "DynamoDbGatewayEndpoint");

        assertThat(resource.get("Condition"))
            .as("mirrors S3GatewayEndpoint — under UseExistingVpc=true the operator supplies their "
                + "own networking and the stack creates no endpoint at all")
            .isEqualTo("CreateNetworking");
        assertThat(properties.get("VpcEndpointType")).isEqualTo("Gateway");
        assertThat(String.valueOf(properties.get("ServiceName"))).contains("dynamodb");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theTableNameIsAStackOutput() {
        var outputs = (Map<String, Object>) template.get("Outputs");

        assertThat(outputs).containsKey("ResultsTableName");
        assertThat((Map<String, Object>) outputs.get("ResultsTableName"))
            .containsEntry("Value", "ResultsTable");
    }

    /**
     * NOT a proof the runner cannot delete: {@code dynamodb:BatchWriteItem} carries
     * {@code DeleteRequest} entries and gets no separate {@code dynamodb:DeleteItem} IAM check, so
     * granting it grants deletes too. That is consistent with existing posture — the runner already
     * holds {@code s3:DeleteObject} on the whole bucket — so the grant stays. This test only proves
     * the runner is never granted read (Scan/Query) or the standalone single-item delete action.
     */
    @Test
    void theRunnerCanWriteAndBatchDeleteResultsButNeverReadOrSingleItemDeleteThem() {
        var actions = InfraFixtures.actions(dynamoDbPolicyDocumentFor("RunnerRole"));

        assertThat(actions).containsExactlyInAnyOrder("dynamodb:PutItem", "dynamodb:BatchWriteItem");
        assertThat(actions).doesNotContain("dynamodb:Scan", "dynamodb:DeleteItem", "dynamodb:Query");
    }

    @Test
    void theOperatorCanReadResultsButNeverWriteThem() {
        var actions = InfraFixtures.actions(dynamoDbPolicyDocumentFor("OperatorRole"));

        assertThat(actions).containsExactlyInAnyOrder("dynamodb:Query", "dynamodb:GetItem");
        assertThat(actions).doesNotContain("dynamodb:PutItem", "dynamodb:Scan", "dynamodb:DeleteItem");
    }

    @Test
    void theOperatorIsGrantedTheIndexArnBecauseAGsiQueryAuthorisesOnTheIndex() {
        assertThat(InfraFixtures.resources(dynamoDbPolicyDocumentFor("OperatorRole")))
            .as("a Query against a GSI authorises on the index ARN, not the table's")
            .anySatisfy(resource -> assertThat(resource).contains("index"));
    }

    @Test
    void noDynamoDbGrantUsesAWildcardResource() {
        assertThat(InfraFixtures.resources(dynamoDbPolicyDocumentFor("RunnerRole"))).doesNotContain("*");
        assertThat(InfraFixtures.resources(dynamoDbPolicyDocumentFor("OperatorRole"))).doesNotContain("*");
    }

    /** The named-policy list entry (not statement) on {@code logicalId} that grants DynamoDB actions. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> dynamoDbPolicyDocumentFor(String logicalId) {
        var policies = (List<Map<String, Object>>) InfraFixtures.properties(template, logicalId).get("Policies");
        return policies.stream()
            .map(policy -> (Map<String, Object>) policy.get("PolicyDocument"))
            .filter(document -> InfraFixtures.actions(document).stream()
                .anyMatch(action -> action.startsWith("dynamodb:")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No dynamodb policy document on " + logicalId));
    }
}
