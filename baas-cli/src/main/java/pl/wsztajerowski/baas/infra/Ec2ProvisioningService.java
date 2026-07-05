package pl.wsztajerowski.baas.infra;

import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.BlockDeviceMapping;
import software.amazon.awssdk.services.ec2.model.EbsBlockDevice;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.HttpTokensState;
import software.amazon.awssdk.services.ec2.model.IamInstanceProfileSpecification;
import software.amazon.awssdk.services.ec2.model.InstanceMetadataOptionsRequest;
import software.amazon.awssdk.services.ec2.model.InstanceNetworkInterfaceSpecification;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.ShutdownBehavior;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;
import software.amazon.awssdk.services.ec2.model.VolumeType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Ec2ProvisioningService {

    private final Ec2Client ec2;

    public Ec2ProvisioningService(Ec2Client ec2) {
        this.ec2 = ec2;
    }

    public String runInstance(String amiId, String instanceType, String subnetId,
                              String securityGroupId, String instanceProfileName,
                              String userData, String requestId, Map<String, String> extraTags) {
        List<Tag> tags = new ArrayList<>(List.of(
            Tag.builder().key("project").value("baas").build(),
            Tag.builder().key("baas:role").value("benchmark-runner").build(),
            Tag.builder().key("baas:request-id").value(requestId).build()
        ));
        extraTags.forEach((k, v) -> tags.add(Tag.builder().key(k).value(v).build()));

        var response = ec2.runInstances(RunInstancesRequest.builder()
            .imageId(amiId)
            .instanceType(InstanceType.fromValue(instanceType))
            .minCount(1)
            .maxCount(1)
            .userData(userData)
            .iamInstanceProfile(IamInstanceProfileSpecification.builder()
                .name(instanceProfileName)
                .build())
            .networkInterfaces(InstanceNetworkInterfaceSpecification.builder()
                .deviceIndex(0)
                .subnetId(subnetId)
                .groups(securityGroupId)
                .associatePublicIpAddress(true)
                .build())
            .instanceInitiatedShutdownBehavior(ShutdownBehavior.TERMINATE)
            .metadataOptions(InstanceMetadataOptionsRequest.builder()
                .httpTokens(HttpTokensState.REQUIRED)
                .httpPutResponseHopLimit(1)
                .build())
            .blockDeviceMappings(BlockDeviceMapping.builder()
                .deviceName("/dev/xvda")
                .ebs(EbsBlockDevice.builder()
                    .volumeSize(30)
                    .volumeType(VolumeType.GP3)
                    .build())
                .build())
            .tagSpecifications(TagSpecification.builder()
                .resourceType(ResourceType.INSTANCE)
                .tags(tags)
                .build())
            .build());

        return response.instances().getFirst().instanceId();
    }

    public void terminateInstance(String instanceId) {
        try {
            ec2.terminateInstances(TerminateInstancesRequest.builder()
                .instanceIds(instanceId)
                .build());
        } catch (Exception e) {
            System.err.println("Warning: failed to terminate instance " + instanceId + ": " + e.getMessage());
        }
    }

    public List<String> listRunningBenchmarkInstances() {
        var response = ec2.describeInstances(r -> r.filters(
            Filter.builder().name("tag:baas:role").values("benchmark-runner").build(),
            Filter.builder().name("instance-state-name").values("running").build()
        ));
        return response.reservations().stream()
            .flatMap(res -> res.instances().stream())
            .map(i -> i.instanceId())
            .toList();
    }
}
