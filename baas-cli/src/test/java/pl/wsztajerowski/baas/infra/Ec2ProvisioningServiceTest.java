package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Tag;

import static org.assertj.core.api.Assertions.assertThat;

class Ec2ProvisioningServiceTest {

    /** Records the one RunInstances request it receives. */
    private static final class CapturingEc2 implements Ec2Client {
        RunInstancesRequest request;
        DescribeInstancesRequest describe;
        DescribeInstancesResponse answer = DescribeInstancesResponse.builder().build();

        @Override
        public DescribeInstancesResponse describeInstances(DescribeInstancesRequest request) {
            this.describe = request;
            return answer;
        }

        @Override
        public RunInstancesResponse runInstances(RunInstancesRequest request) {
            this.request = request;
            return RunInstancesResponse.builder()
                .instances(Instance.builder().instanceId("i-0123456789abcdef0").build())
                .build();
        }

        @Override
        public String serviceName() {
            return "ec2";
        }

        @Override
        public void close() {
        }
    }

    /**
     * Only the fixed tags reach the instance. A caller tag there once made `--tag project=…` a
     * duplicate key, which EC2 rejects for the whole launch (confirmed by a dry-run: "Duplicate tag
     * key 'project' specified"); result tags reach the result through the runner instead.
     */
    @Test
    void theInstanceCarriesTheFixedTagsAndNothingElse() {
        var ec2 = new CapturingEc2();

        new Ec2ProvisioningService(ec2).runInstance("ami-1", "c5.2xlarge", "subnet-1", "sg-1",
            "baas-123456789012-profile-runner", "#!/bin/bash", "20261002T080250645Z-264f5dfb");

        var tags = ec2.request.tagSpecifications().getFirst().tags();
        assertThat(tags).extracting(Tag::key)
            .containsExactlyInAnyOrder("project", "baas-role", "baas-request-id");
        assertThat(tags).extracting(Tag::key).doesNotHaveDuplicates();
        assertThat(tags).filteredOn(tag -> tag.key().equals("baas-request-id"))
            .extracting(Tag::value).containsExactly("20261002T080250645Z-264f5dfb");
    }

    /**
     * W2: teardown's live-runner gate. A runner launched seconds earlier is still `pending`, and
     * deleting the stack then pulls its role, subnet and image out from under it.
     */
    @Test
    void theTeardownGateCountsBootingRunnersAsLive() {
        var ec2 = new CapturingEc2();

        new Ec2ProvisioningService(ec2).listRunningBenchmarkInstances();

        assertThat(ec2.describe.filters()).extracting(Filter::name, Filter::values)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("tag:baas-role", java.util.List.of("benchmark-runner")),
                org.assertj.core.groups.Tuple.tuple("instance-state-name", java.util.List.of("pending", "running")));
    }

    /** Teardown and `baas runs list` name runs from the tag, with no read of the results table. */
    @Test
    void eachLiveRunnerCarriesItsRunIdFromTheTag() {
        var ec2 = new CapturingEc2();
        ec2.answer = DescribeInstancesResponse.builder().reservations(r -> r.instances(
            Instance.builder().instanceId("i-1").state(s -> s.name("running"))
                .tags(Tag.builder().key("baas-request-id").value("20261003T000000000Z-a3f9c21b").build()).build(),
            Instance.builder().instanceId("i-2").state(s -> s.name("pending")).build())).build();

        assertThat(new Ec2ProvisioningService(ec2).listRunningBenchmarkInstances()).containsExactly(
            new Ec2ProvisioningService.LiveRunner("i-1", "running", "20261003T000000000Z-a3f9c21b"),
            new Ec2ProvisioningService.LiveRunner("i-2", "pending", null));
    }

    @Test
    void aRunsLiveInstanceIsFoundByItsRunIdTag() {
        var ec2 = new CapturingEc2();
        ec2.answer = DescribeInstancesResponse.builder()
            .reservations(r -> r.instances(Instance.builder().instanceId("i-9").build())).build();

        assertThat(new Ec2ProvisioningService(ec2).findLive("run-1")).contains("i-9");
        assertThat(ec2.describe.filters()).extracting(Filter::name, Filter::values)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("tag:baas-request-id", java.util.List.of("run-1")),
                org.assertj.core.groups.Tuple.tuple("instance-state-name", java.util.List.of("pending", "running")));
    }

    /** An instance type newer than the SDK's enum must reach EC2 as typed, not as 'null'. */
    @Test
    void anInstanceTypeTheSdkDoesNotKnowIsSentAsTyped() {
        var ec2 = new CapturingEc2();

        new Ec2ProvisioningService(ec2).runInstance("ami-1", "z99.future-large", "subnet-1", "sg-1",
            "profile", "#!/bin/bash", "run-1");

        assertThat(ec2.request.instanceTypeAsString()).isEqualTo("z99.future-large");
    }
}
