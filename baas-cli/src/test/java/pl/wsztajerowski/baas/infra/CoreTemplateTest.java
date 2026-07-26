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
    void workingBucketSurvivesStackDeletion() {
        var bucket = InfraFixtures.resource(template, "S3MainBucket");

        assertThat(bucket)
            .as("teardown promises the bucket is retained — that must be declared, not a side effect of a failing delete")
            .containsEntry("DeletionPolicy", "Retain")
            .containsEntry("UpdateReplacePolicy", "Retain");
    }
}
