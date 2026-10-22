package pl.wsztajerowski.baas.jobs;

import pl.wsztajerowski.baas.aws.AwsClientFactory;
import pl.wsztajerowski.baas.model.JobItem;
import pl.wsztajerowski.baas.session.BaasConfig;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.InstanceInformationStringFilter;
import software.amazon.awssdk.services.ssm.model.InvalidDocumentException;
import software.amazon.awssdk.services.ssm.model.PingStatus;

import java.util.Optional;
import java.util.Set;

/** {@link JobsConnectSubcommand.Sources} over the operator session's clients. */
final class AwsConnectSources implements JobsConnectSubcommand.Sources {

    private static final Set<String> ENDED = Set.of("shutting-down", "terminated", "stopping", "stopped");

    private final String region;
    private final DynamoDbClient dynamoDb;
    private final Ec2Client ec2;
    private final SsmClient ssm;
    private final DynamoDbJobRecorder recorder;
    private final Ec2ProvisioningService instances;

    AwsConnectSources(AwsClientFactory factory, BaasConfig config) {
        this.region = factory.region();
        this.dynamoDb = factory.dynamoDb();
        this.ec2 = factory.ec2();
        this.ssm = factory.ssm();
        this.recorder = new DynamoDbJobRecorder(dynamoDb, config.resultsTable());
        this.instances = new Ec2ProvisioningService(ec2, config.stackName());
    }

    @Override
    public String region() {
        return region;
    }

    @Override
    public Optional<JobItem> job(String jobId) {
        return recorder.find(jobId);
    }

    @Override
    public Optional<Ec2ProvisioningService.LiveInstance> liveInstance(String jobId) {
        return instances.findLiveInstance(jobId);
    }

    @Override
    public boolean agentOnline(String instanceId) {
        return ssm.describeInstanceInformation(r -> r.filters(InstanceInformationStringFilter.builder()
                .key("InstanceIds").values(instanceId).build()))
            .instanceInformationList().stream()
            .anyMatch(info -> info.pingStatus() == PingStatus.ONLINE);
    }

    /**
     * Only an instance EC2 reports as ending or ended is not live. A describe that fails counts as
     * live, and quietly: a hiccup must not cut a session, and nothing may be written over the shell.
     */
    @Override
    public boolean instanceLive(String instanceId) {
        try {
            return ec2.describeInstances(r -> r.instanceIds(instanceId)).reservations().stream()
                .flatMap(reservation -> reservation.instances().stream())
                .findFirst()
                .map(instance -> !ENDED.contains(instance.state().nameAsString()))
                .orElse(true);
        } catch (RuntimeException e) {
            return true;
        }
    }

    @Override
    public SessionLauncher.Started start(String instanceId, String document) {
        try {
            var started = ssm.startSession(r -> r.target(instanceId).documentName(document));
            return new SessionLauncher.Started(started.sessionId(), started.streamUrl(), started.tokenValue());
        } catch (InvalidDocumentException e) {
            throw new JobsConnectSubcommand.Sources.Refused(e.getMessage(), e);
        }
    }

    @Override
    public void terminate(String sessionId) {
        ssm.terminateSession(r -> r.sessionId(sessionId));
    }

    @Override
    public void close() {
        dynamoDb.close();
        ec2.close();
        ssm.close();
    }
}
