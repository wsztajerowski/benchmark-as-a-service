package pl.wsztajerowski.baas.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.baas.jobs.JobSession;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class Ec2ProvisioningService implements JobSession.Instances {

    static final String JOB_ID_TAG = "baas-job-id";
    static final String DEPLOYMENT_TAG = "baas-deployment";

    private static final Logger logger = LoggerFactory.getLogger(Ec2ProvisioningService.class);

    private final Ec2Client ec2;

    public Ec2ProvisioningService(Ec2Client ec2) {
        this.ec2 = ec2;
    }

    /**
     * The instance carries the fixed tags and nothing else: {@code baas-role} (RunnerRole's
     * terminate condition), {@code baas-job-id} (finding a job's instance), {@code baas-deployment}
     * (scoping every live-runner query to its deployment, so two deployments in one region never
     * count each other's runners) and {@code project=baas}. Caller {@code --tag}s belong to the stored result and reach
     * it through the runner. Copying them here too sent a {@code --tag project=…} as a second
     * {@code project} key, which EC2 rejects outright, and exposed every result tag to EC2's own
     * limits (256-character values, a reserved {@code aws:} prefix, 50 tags) — failures that land
     * after the JAR upload. There is deliberately no parameter for extra tags.
     */
    static List<Tag> instanceTags(String jobId, String deployment) {
        return List.of(
            Tag.builder().key("project").value("baas").build(),
            Tag.builder().key("baas-role").value("benchmark-runner").build(),
            Tag.builder().key(JOB_ID_TAG).value(jobId).build(),
            Tag.builder().key(DEPLOYMENT_TAG).value(deployment).build());
    }

    public String runInstance(String amiId, String instanceType, String subnetId,
                              String securityGroupId, String instanceProfileName,
                              String userData, String jobId, String deployment) {
        List<Tag> tags = instanceTags(jobId, deployment);
        logger.debug("Launching {} from {} in subnet {} (sg {}, instance profile {}) with tags {}",
            instanceType, amiId, subnetId, securityGroupId, instanceProfileName,
            tags.stream().collect(Collectors.toMap(Tag::key, Tag::value)));

        var response = ec2.runInstances(RunInstancesRequest.builder()
            .imageId(amiId)
            // The string overload, not InstanceType.fromValue: a type newer than this SDK's enum maps
            // to UNKNOWN_TO_SDK_VERSION, which serialises as 'null', so EC2 rejected every instance
            // family released after the SDK was built — found by a forced launch failure.
            .instanceType(instanceType)
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

    private boolean describeFailureReported = false;

    /**
     * Current EC2 state name, or "unknown" if the instance cannot be described.
     * Used to fail a job fast when the runner dies before recording its outcome on the job item.
     *
     * <p>A persistent failure here (denied permission, expired session) silently
     * disables that fail-fast, so the first one is reported rather than swallowed.
     */
    @Override
    public String state(String instanceId) {
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
                logger.warn("Cannot read the state of {} ({}) — this job will poll until the "
                    + "wall-clock cap instead of failing fast if the runner dies.", instanceId, e.getMessage());
            }
            return "unknown";
        }
    }

    /** A runner instance that is pending or running, and the job it belongs to. */
    public record LiveRunner(String instanceId, String state, String jobId) {}

    /**
     * Pending counts as live: a job launched seconds before a teardown is still booting, and
     * deleting the stack then pulls its role, subnet and bucket out from under it.
     *
     * <p>The job id comes from the {@code baas-job-id} tag, which {@code RunInstances} applies
     * in the same call that creates the instance, so every runner carries it. Reading it here is
     * what lets teardown name jobs without any access to the results table, and lets
     * {@code baas jobs list} resolve every job's liveness with this one call however many jobs
     * vanished before.
     */
    public List<LiveRunner> listRunningBenchmarkInstances(String deployment) {
        return ec2.describeInstancesPaginator(r -> r.filters(
                Filter.builder().name("tag:baas-role").values("benchmark-runner").build(),
                Filter.builder().name("tag:" + DEPLOYMENT_TAG).values(deployment).build(),
                Filter.builder().name("instance-state-name").values("pending", "running").build()))
            .reservations().stream()
            .flatMap(res -> res.instances().stream())
            .map(i -> new LiveRunner(i.instanceId(), i.state().nameAsString(), tag(i, JOB_ID_TAG)))
            .toList();
    }


    /** The pending or running instance of one job, found by its {@code baas-job-id} tag. */
    @Override
    public Optional<String> findLive(String jobId) {
        return ec2.describeInstancesPaginator(r -> r.filters(
                Filter.builder().name("tag:" + JOB_ID_TAG).values(jobId).build(),
                Filter.builder().name("instance-state-name").values("pending", "running").build()))
            .reservations().stream()
            .flatMap(res -> res.instances().stream())
            .map(Instance::instanceId)
            .findFirst();
    }

    /**
     * Throws when the request fails. {@link JobSession} decides whether that is fatal: it is for
     * {@code baas jobs terminate}, which must not report success over a live instance, and is
     * logged for the shutdown hook, where nothing is left to handle it.
     */
    @Override
    public void terminate(String instanceId) {
        ec2.terminateInstances(TerminateInstancesRequest.builder().instanceIds(instanceId).build());
    }

    private static String tag(Instance instance, String key) {
        return instance.tags().stream()
            .filter(t -> key.equals(t.key()))
            .map(Tag::value)
            .findFirst()
            .orElse(null);
    }
}
