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

        // The `baas-` namespace lives inside the prefix value now, so the bucket is the bare
        // stem. Composing it here again would produce `baas-baas-123456789012`.
        assertThat(bucket.get("BucketName")).isEqualTo("${ResourceNamePrefix}");
    }

/**
     * Inverted, not deleted. This assertion previously pinned 27017's PRESENCE, because Atlas does
     * not serve clients on 443 and omitting the rule made every job fail at the database write.
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
            .as("443 only: S3, DynamoDB and the EC2 API the watchdog calls; port 80 left with the "
                + "image build, which has its own group")
            .hasSize(1);
    }

    /**
     * The bake needs the internet (dnf over 80, async-profiler from GitHub) and the runner needs
     * none of it, because the bake already happened. Sharing one group handed the runner the
     * builder's egress — the invariant that it contacts nothing outside the account then rested on
     * the user-data script alone.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theImageBuildHasItsOwnSecurityGroupAndOnlyItKeepsPort80() {
        var groupIds = (List<Object>)
            InfraFixtures.properties(template, "RunnerImageInfrastructure").get("SecurityGroupIds");
        assertThat(groupIds).hasSize(1);
        assertThat((List<Object>) groupIds.get(0))
            .as("!If [CreateNetworking, build group, the operator's existing group]")
            .containsExactly("CreateNetworking", "ImageBuildSecurityGroup", "ExistingSecurityGroupId");

        var runnerEgress = (List<Map<String, Object>>)
            InfraFixtures.properties(template, "RunnerSecurityGroup").get("SecurityGroupEgress");
        assertThat(runnerEgress).noneSatisfy(rule -> assertThat(rule.get("FromPort")).isEqualTo(80));

        var buildEgress = (List<Map<String, Object>>)
            InfraFixtures.properties(template, "ImageBuildSecurityGroup").get("SecurityGroupEgress");
        assertThat(buildEgress).extracting(rule -> rule.get("FromPort")).containsExactlyInAnyOrder(443, 80);

        assertThat(InfraFixtures.resource(template, "ImageBuildSecurityGroup").get("Condition"))
            .as("under --use-existing-vpc no group is created in the operator's VPC")
            .isEqualTo("CreateNetworking");
    }

    /**
     * GroupDescription is immutable: editing it makes CloudFormation replace the group, moving its
     * id, and the replacement fails outright while a runner still holds the old one. That happened
     * once by accident (removing 27017 also touched the text). Its "443/80" is now out of date on
     * purpose — see the template comment.
     */
    @Test
    void theRunnerSecurityGroupDescriptionIsNeverEdited() {
        assertThat(InfraFixtures.properties(template, "RunnerSecurityGroup").get("GroupDescription"))
            .isEqualTo("Benchmark runner security group. No inbound; outbound 443/80 only.");
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

    /**
     * Suspended, not enabled: results are write-once and a job id carries 32 bits of entropy, so
     * the overwrite versioning guarded against no longer has a mechanism. Stated rather than
     * implied — there is now no server-side recovery from one.
     */
    @Test
    @SuppressWarnings("unchecked")
    void bucketVersioningIsSuspended() {
        var versioning = (Map<String, Object>)
            InfraFixtures.properties(template, "S3MainBucket").get("VersioningConfiguration");

        assertThat(versioning).containsEntry("Status", "Suspended");
    }

    /**
     * The deleted rule's premise — everything under {@code jobs/} is re-creatable from source — is
     * exactly what the unified layout falsifies: results live there now, and the uploaded JAR is
     * the only copy of what a measurement actually ran. Asserted as an absence so the rule cannot
     * come back unnoticed.
     */
    @Test
    @SuppressWarnings("unchecked")
    void noLifecycleRuleExpiresCurrentObjectsUnderJobs() {
        var lifecycle = (Map<String, Object>)
            InfraFixtures.properties(template, "S3MainBucket").get("LifecycleConfiguration");
        var rules = (List<Map<String, Object>>) lifecycle.get("Rules");

        assertThat(rules).allSatisfy(rule ->
            assertThat(rule).doesNotContainKey("ExpirationInDays"));
        assertThat(rules).extracting(rule -> rule.get("Id"))
            .doesNotContain("expire-uploaded-benchmark-jars");
    }

    /** Suspension is not the same as never having versioned; existing versions persist. */
    @Test
    @SuppressWarnings("unchecked")
    void theNoncurrentRulesSurviveSuspension() {
        var lifecycle = (Map<String, Object>)
            InfraFixtures.properties(template, "S3MainBucket").get("LifecycleConfiguration");
        var rules = (List<Map<String, Object>>) lifecycle.get("Rules");

        assertThat(rules).extracting(rule -> rule.get("Id"))
            .contains("expire-noncurrent-versions", "expire-orphaned-delete-markers",
                "abort-incomplete-uploads");
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

        var components = (List<Object>)
            InfraFixtures.properties(template, "RunnerImageRecipe").get("Components");
        assertThat(components.getFirst()).isEqualTo(Map.of("ComponentArn", "RunnerImageComponent.Arn"));
        assertThat(components.getLast()).isEqualTo(Map.of("ComponentArn", "RunnerImageContractComponent.Arn"));
        assertThat((List<Object>) components.get(1))
            .contains(Map.of("ComponentArn", "RunnerImageExtensionComponent.Arn"));

        var outputs = (Map<String, Object>) template.get("Outputs");
        assertThat((Map<String, Object>) outputs.get("RunnerImagePipelineArn"))
            .as("baas admin build-image passes this value verbatim to StartImagePipelineExecution")
            .containsEntry("Value", "RunnerImagePipeline.Arn");
    }

    /**
     * Base, then the operator's extension, then the contract — last, so whatever the extension did,
     * the checks see the result. The extension's entry is conditional: no extension, no component.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theRecipeRunsBaseThenExtensionThenContract() {
        var components = (List<Object>)
            InfraFixtures.properties(template, "RunnerImageRecipe").get("Components");

        assertThat(components).hasSize(3);
        assertThat((List<Object>) components.get(1))
            .as("!If [HasRunnerImageExtension, <extension>, AWS::NoValue]")
            .containsExactly("HasRunnerImageExtension",
                Map.of("ComponentArn", "RunnerImageExtensionComponent.Arn"), "AWS::NoValue");

        assertThat(InfraFixtures.resource(template, "RunnerImageExtensionComponent"))
            .containsEntry("Condition", "HasRunnerImageExtension");
        var conditions = (Map<String, Object>) template.get("Conditions");
        assertThat(conditions.get("HasRunnerImageExtension"))
            .as("an empty extension parameter means no extension")
            .isEqualTo(List.of(List.of("RunnerImageExtensionData", "")));
    }

    /**
     * Components and Version are create-only on a recipe, so a changed component list replaces it,
     * and the replacement needs an unused version: the recipe's is derived, never the base's.
     */
    @Test
    void derivedVersionsVersionTheRecipeAndTheContract() {
        assertThat(InfraFixtures.properties(template, "RunnerImageRecipe"))
            .containsEntry("Version", "RunnerImageRecipeVersion");
        assertThat(InfraFixtures.properties(template, "RunnerImageContractComponent"))
            .as("the contract carries the label, which moves only with the recipe")
            .containsEntry("Version", "RunnerImageRecipeVersion")
            .containsEntry("Data", "RunnerImageContractData");
        assertThat(InfraFixtures.properties(template, "RunnerImageExtensionComponent"))
            .containsEntry("Version", "RunnerImageExtensionVersion")
            .containsEntry("Data", "RunnerImageExtensionData");
        assertThat(InfraFixtures.properties(template, "RunnerImageComponent"))
            .as("the base alone keeps the hand-bumped version")
            .containsEntry("Version", "RunnerImageVersion");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theAmiIsTaggedWithTheLabel() {
        var distributions = (List<Map<String, Object>>)
            InfraFixtures.properties(template, "RunnerImageDistribution").get("Distributions");
        var amiTags = (Map<String, Object>)
            ((Map<String, Object>) distributions.getFirst().get("AmiDistributionConfiguration")).get("AmiTags");

        assertThat(amiTags)
            .as("baas admin image and every job read the image's identity from this tag; with an "
                + "extension it must name the extension too, or the image passes for a stock one")
            .containsEntry("baas-image-version", "RunnerImageLabel");
    }

    @Test
    @SuppressWarnings("unchecked")
    void imageTestsRunSoTheContractCanFailTheBuild() {
        var tests = (Map<String, Object>)
            InfraFixtures.properties(template, "RunnerImagePipeline").get("ImageTestsConfiguration");

        assertThat(tests)
            .as("the contract's checks live in its test phase; with tests disabled they never run "
                + "and an image that broke BaaS is published")
            .containsEntry("ImageTestsEnabled", true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyImageParameterThePlanSendsIsDeclared() {
        var parameters = (Map<String, Object>) template.get("Parameters");

        assertThat(parameters)
            .as("a planned parameter the template does not declare is rejected by CloudFormation")
            .containsKeys(RunnerImageParameters.ALL.toArray(String[]::new));
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
            .as("named slots or a second pointer would let two jobs disagree about which image "
                + "'the' image is")
            .containsExactly(
                "arn:${AWS::Partition}:ssm:${AWS::Region}:${AWS::AccountId}:parameter/${ResourceNamePrefix}/runner/ami-id");

        var outputs = (Map<String, Object>) template.get("Outputs");
        assertThat(outputs).containsKey("RunnerAmiParameterName");
    }

    /** Nothing survives a teardown: a deployment's data leaves with it unless exported first. */
    @Test
    void theWorkingBucketIsDeletedWithTheStack() {
        assertThat(InfraFixtures.resource(template, "S3MainBucket"))
            .containsEntry("DeletionPolicy", "Delete")
            .containsEntry("UpdateReplacePolicy", "Delete");
    }

    @Test
    void theResultsTableIsDeletedWithTheStack() {
        assertThat(InfraFixtures.resource(template, "ResultsTable"))
            .containsEntry("DeletionPolicy", "Delete")
            .containsEntry("UpdateReplacePolicy", "Delete");
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
    void theResultsTableHasExactlyOneIndexKeyedOnJobId() {
        var properties = InfraFixtures.properties(template, "ResultsTable");
        var indexes = (List<Map<String, Object>>) properties.get("GlobalSecondaryIndexes");

        assertThat(indexes).hasSize(1);
        assertThat(indexes.get(0).get("IndexName")).isEqualTo(ResultKeys.JOB_ID_INDEX_NAME);

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

        assertThat(actions).containsExactlyInAnyOrder(
            "dynamodb:PutItem", "dynamodb:BatchWriteItem", "dynamodb:UpdateItem");
        assertThat(actions).doesNotContain("dynamodb:Scan", "dynamodb:DeleteItem", "dynamodb:Query",
            "dynamodb:GetItem");
    }

    /**
     * The runner's puts are confined to measurement partitions, so it cannot forge a job item,
     * and its one update is confined to the JOB partition, so it cannot rewrite a measurement.
     */
    @Test
    void theRunnerPutsOnlyMeasurementsAndUpdatesOnlyJobItems() {
        var runner = dynamoDbPolicyDocumentFor("RunnerRole");

        assertThat(leadingKeysCondition(runner, "dynamodb:PutItem"))
            .isEqualTo(Map.of("ForAllValues:StringLike", List.of("RESULT#*")));
        assertThat(leadingKeysCondition(runner, "dynamodb:BatchWriteItem"))
            .isEqualTo(Map.of("ForAllValues:StringLike", List.of("RESULT#*")));
        assertThat(leadingKeysCondition(runner, "dynamodb:UpdateItem"))
            .isEqualTo(Map.of("ForAllValues:StringEquals", List.of("JOB")));
    }

    /** The operator's first write on the table, and it reaches job items only. */
    @Test
    void theOperatorUpdatesOnlyJobItems() {
        assertThat(leadingKeysCondition(dynamoDbPolicyDocumentFor("OperatorRole"), "dynamodb:UpdateItem"))
            .isEqualTo(Map.of("ForAllValues:StringEquals", List.of("JOB")));
    }

    /**
     * Scan was pinned as absent here until the history migration needed to enumerate runs.
     * It is a read, so the property this test defends — the operator never writes — is
     * unchanged, and it grants no new reach: Query already covers the table and its indexes,
     * so every row a Scan returns was reachable before. The write exclusions stay exact.
     * The runner still must not hold Scan; that is pinned separately above.
     */
    @Test
    void theOperatorCanReadResultsButNeverWriteThem() {
        var actions = InfraFixtures.actions(dynamoDbPolicyDocumentFor("OperatorRole"));

        assertThat(actions).containsExactlyInAnyOrder(
            "dynamodb:Query", "dynamodb:Scan", "dynamodb:GetItem", "dynamodb:UpdateItem");
        assertThat(actions).doesNotContain(
            "dynamodb:PutItem", "dynamodb:BatchWriteItem", "dynamodb:DeleteItem");
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

    // ─── GitHub OIDC federation on the operator role ─────────────────────────────
    //
    // The intrinsic-tolerant YAML loader collapses !If to its argument list, so the conditional
    // statement reads as ["FederateGitHub", {the statement}, "AWS::NoValue"] — which is exactly
    // the structure worth asserting: present under the condition, absent otherwise, by
    // construction rather than by a deploy nobody runs in a test.

    @Test
    void theFederatedStatementIsConditionalOnTheFederationParameters() {
        var branches = federatedStatementBranches();

        assertThat(branches.get(0)).isEqualTo("FederateGitHub");
        assertThat(branches.get(2))
            .as("a deployment supplying no federation parameters must deploy exactly as before")
            .isEqualTo("AWS::NoValue");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theFederatedPrincipalAssumesTheOperatorRoleDirectly() {
        var statement = (Map<String, Object>) federatedStatementBranches().get(1);

        assertThat(statement).containsEntry("Action", "sts:AssumeRoleWithWebIdentity");
        assertThat((Map<String, Object>) statement.get("Principal"))
            .as("no intermediate role stands between the workload identity and the operator role")
            .containsEntry("Federated", "GitHubOidcProviderArn");
    }

    /**
     * One {@code StringLike} value per repository, composed by {@code baas admin setup} from
     * {@code --github-org} and each {@code --github-repo}. The template refs the list directly:
     * CloudFormation cannot iterate one, and every in-template trick for it leans on {@code
     * Fn::Sub} re-scanning substituted text, which it does not do.
     */
    @Test
    @SuppressWarnings("unchecked")
    void everyTrustedRepositoryComesFromTheRepositoryList() {
        var statement = (Map<String, Object>) federatedStatementBranches().get(1);
        var condition = (Map<String, Object>) statement.get("Condition");

        assertThat((Map<String, Object>) condition.get("StringLike"))
            .containsEntry("token.actions.githubusercontent.com:sub", "GitHubRepo");
        assertThat((Map<String, Object>) condition.get("StringEquals"))
            .as("without an audience check the token of any AWS-federated workload would do")
            .containsEntry("token.actions.githubusercontent.com:aud", "sts.amazonaws.com");
    }

    /**
     * Additive, in both directions: federating must not lock local operators out, and revoking
     * must not lock anyone out either. Finding S9 (the account-root principal) therefore stays
     * open even though this change edits that exact trust policy.
     */
    @Test
    void theAccountRootPrincipalSurvivesAlongsideTheFederatedOne() {
        assertThat(operatorTrustStatements().get(0))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
            .containsEntry("Action", "sts:AssumeRole")
            .extracting("Principal")
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.map(String.class, Object.class))
            .containsEntry("AWS", "arn:${AWS::Partition}:iam::${AWS::AccountId}:root");
    }

    /**
     * A session that expired mid-poll would leave the shell watchdog as the only termination
     * layer: the CI job goes red, the measurement is fine and the full instance-lifetime is billed.
     * Strictly above the 7500 s wall-clock default, because terminating the instance needs
     * credentials too — after the job has finished.
     */
    @Test
    void theOperatorSessionOutlastsTheJobItPolls() {
        assertThat((Integer) InfraFixtures.properties(template, "OperatorRole")
            .get("MaxSessionDuration"))
            .isGreaterThan(7500);
    }

    @Test
    @SuppressWarnings("unchecked")
    void federationAddsNoResourceToTheCoreStack() {
        var resources = (Map<String, Object>) template.get("Resources");

        assertThat(resources.values())
            .as("the identity provider is account-global and lives in the CI stack")
            .noneMatch(resource -> "AWS::IAM::OIDCProvider"
                .equals(((Map<String, Object>) resource).get("Type")));
        assertThat(resources).doesNotContainKey("WorkflowRole");
    }

    @SuppressWarnings("unchecked")
    private List<Object> operatorTrustStatements() {
        var trust = (Map<String, Object>)
            InfraFixtures.properties(template, "OperatorRole").get("AssumeRolePolicyDocument");
        return (List<Object>) trust.get("Statement");
    }

    /** The collapsed {@code !If} arms of the conditional federated statement. */
    @SuppressWarnings("unchecked")
    private List<Object> federatedStatementBranches() {
        return (List<Object>) operatorTrustStatements().get(1);
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

    /**
     * The {@code dynamodb:LeadingKeys} condition of the one statement granting {@code action}, as
     * operator → values. Fails if the action is granted by more than one statement, which would
     * let an unconditioned copy widen the conditioned one.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> leadingKeysCondition(Map<String, Object> document, String action) {
        var statements = ((List<Map<String, Object>>) document.get("Statement")).stream()
            .filter(statement -> {
                Object raw = statement.get("Action");
                return raw instanceof List<?> list ? list.contains(action) : action.equals(raw);
            })
            .toList();
        assertThat(statements).as("statements granting " + action).hasSize(1);
        var condition = (Map<String, Map<String, Object>>) statements.getFirst().get("Condition");
        assertThat(condition).as("condition on " + action).isNotNull();
        var byOperator = new java.util.TreeMap<String, Object>();
        condition.forEach((operator, keys) -> byOperator.put(operator, keys.get("dynamodb:LeadingKeys")));
        return byOperator;
    }
}
