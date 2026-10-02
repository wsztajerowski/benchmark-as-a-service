package pl.wsztajerowski.baas.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.util.List;
import java.util.stream.Collectors;

public class Ec2ProvisioningService {

    private static final Logger logger = LoggerFactory.getLogger(Ec2ProvisioningService.class);

    private final Ec2Client ec2;

    public Ec2ProvisioningService(Ec2Client ec2) {
        this.ec2 = ec2;
    }

    /**
     * The instance carries the fixed tags and nothing else: {@code baas-role} (RunnerRole's
     * terminate condition and teardown's live-runner gate), {@code baas-request-id} (finding a run's
     * instance) and {@code project=baas}. Caller {@code --tag}s belong to the stored result and reach
     * it through the runner. Copying them here too sent a {@code --tag project=…} as a second
     * {@code project} key, which EC2 rejects outright, and exposed every result tag to EC2's own
     * limits (256-character values, a reserved {@code aws:} prefix, 50 tags) — failures that land
     * after the JAR upload. There is deliberately no parameter for extra tags.
     */
    static List<Tag> instanceTags(String requestId) {
        return List.of(
            Tag.builder().key("project").value("baas").build(),
            Tag.builder().key("baas-role").value("benchmark-runner").build(),
            Tag.builder().key("baas-request-id").value(requestId).build());
    }

    public String runInstance(String amiId, String instanceType, String subnetId,
                              String securityGroupId, String instanceProfileName,
                              String userData, String requestId) {
        List<Tag> tags = instanceTags(requestId);
        logger.debug("Launching {} from {} in subnet {} (sg {}, instance profile {}) with tags {}",
            instanceType, amiId, subnetId, securityGroupId, instanceProfileName,
            tags.stream().collect(Collectors.toMap(Tag::key, Tag::value)));

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
            logger.warn("Failed to terminate instance {}: {}", instanceId, e.getMessage());
        }
    }

    private boolean describeFailureReported = false;

    /**
     * Current EC2 state name, or "unknown" if the instance cannot be described.
     * Used to fail a run fast when the runner dies before writing its sentinel.
     *
     * <p>A persistent failure here (denied permission, expired session) silently
     * disables that fail-fast, so the first one is reported rather than swallowed.
     */
    public String instanceState(String instanceId) {
        try {
            var response = ec2.describeInstances(r -> r.instanceIds(instanceId));
            return response.reservations().stream()
                .flatMap(reservation -> reservation.instances().stream())
                .findFirst()
                .map(instance -> instance.state().nameAsString())
                .orElse("unknown");
        } catch (Exception e) {
            if (!describeFailureReported) {
                describeFailureReported = true;
                logger.warn("Cannot read the state of {} ({}) — this run will poll until the "
                    + "wall-clock cap instead of failing fast if the runner dies.", instanceId, e.getMessage());
            }
            return "unknown";
        }
    }

    /**
     * Pending counts as live: a run launched seconds before a teardown is still booting, and
     * deleting the stack then pulls its role, subnet and bucket out from under it.
     */
    public List<String> listRunningBenchmarkInstances() {
        var response = ec2.describeInstances(r -> r.filters(
            Filter.builder().name("tag:baas-role").values("benchmark-runner").build(),
            Filter.builder().name("instance-state-name").values("pending", "running").build()
        ));
        return response.reservations().stream()
            .flatMap(res -> res.instances().stream())
            .map(i -> i.instanceId())
            .toList();
    }
}
