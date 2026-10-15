package pl.wsztajerowski.baas.jobs;

import pl.wsztajerowski.baas.config.DeploymentNames;
import pl.wsztajerowski.baas.infra.AwsClientFactory;
import pl.wsztajerowski.baas.infra.CloudFormationService;
import pl.wsztajerowski.baas.infra.Ec2ProvisioningService;
import pl.wsztajerowski.baas.infra.ImageBuilderService;
import pl.wsztajerowski.baas.infra.RunnerImage;
import pl.wsztajerowski.baas.infra.RunnerJarResolver;
import pl.wsztajerowski.baas.infra.S3UploadService;
import pl.wsztajerowski.baas.results.ResultRow;
import pl.wsztajerowski.baas.results.ResultsQueryService;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** {@link RunAws} over the operator session's clients. */
public final class AwsRunAws implements RunAws {

    private final AwsClientFactory factory;

    public AwsRunAws(AwsClientFactory factory) {
        this.factory = factory;
    }

    @Override
    public String region() {
        return factory.region();
    }

    @Override
    public Optional<RunnerImage> currentImage(String amiPointer) {
        try (var imageBuilder = factory.imageBuilder(); var ec2 = factory.ec2(); var ssm = factory.ssm()) {
            return new ImageBuilderService(imageBuilder, ec2, ssm).currentImage(amiPointer);
        }
    }

    @Override
    public Map<String, String> stackOutputs(String stack) {
        try (var cf = factory.cloudFormation()) {
            return new CloudFormationService(cf).getStackOutputs(stack);
        }
    }

    @Override
    public void upload(Path file, String bucket, String key) {
        withS3(s3 -> {
            s3.upload(file, bucket, key);
            return null;
        });
    }

    @Override
    public void putText(String bucket, String key, String text) {
        withS3(s3 -> {
            s3.putText(bucket, key, text);
            return null;
        });
    }

    @Override
    public String pinnedRunnerJar(String bucket, String version, String sourceRepo) {
        try (var s3 = factory.s3()) {
            return RunnerJarResolver.resolve(s3, bucket, version, sourceRepo);
        }
    }

    @Override
    public JobRecorder recorder(String table) {
        return new DynamoDbJobRecorder(factory.dynamoDb(), table);
    }

    @Override
    public JobRecorder stopRecorder(String table) {
        return new DynamoDbJobRecorder(factory.dynamoDb(JobSession.STOP_WRITE_TIMEOUT), table);
    }

    @Override
    public Instances instances(String deployment) {
        var ec2 = new Ec2ProvisioningService(factory.ec2(), DeploymentNames.of(deployment).stack());
        return new Instances() {
            @Override
            public String runInstance(String amiId, String instanceType, String subnetId, String securityGroupId,
                                      String instanceProfileName, String userData, String jobId, String deployment) {
                return ec2.runInstance(amiId, instanceType, subnetId, securityGroupId, instanceProfileName,
                    userData, jobId, deployment);
            }

            @Override
            public String state(String instanceId) {
                return ec2.state(instanceId);
            }

            @Override
            public Optional<String> findLive(String jobId) {
                return ec2.findLive(jobId);
            }

            @Override
            public void terminate(String instanceId) {
                ec2.terminate(instanceId);
            }
        };
    }

    @Override
    public List<ResultRow> jobRows(String table, String jobId) {
        try (var results = new ResultsQueryService(factory.dynamoDb(), table)) {
            return results.queryByJobId(jobId);
        }
    }

    private <T> T withS3(Function<S3UploadService, T> action) {
        try (var s3 = factory.s3()) {
            return action.apply(new S3UploadService(s3));
        }
    }
}
