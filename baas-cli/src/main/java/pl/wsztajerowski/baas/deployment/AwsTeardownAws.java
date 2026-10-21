package pl.wsztajerowski.baas.deployment;

import pl.wsztajerowski.baas.aws.AwsClientFactory;
import pl.wsztajerowski.baas.aws.CloudFormationService;
import pl.wsztajerowski.baas.aws.S3UploadService;
import pl.wsztajerowski.baas.image.ImageBuilderService;
import pl.wsztajerowski.baas.jobs.Ec2ProvisioningService;
import pl.wsztajerowski.baas.jobs.Ec2ProvisioningService.LiveRunner;

import java.util.List;
import java.util.Map;

/** {@link TeardownAws} over the deployer's clients: one client per call, closed after it. */
final class AwsTeardownAws implements TeardownAws {

    private final AwsClientFactory factory;

    AwsTeardownAws(AwsClientFactory factory) {
        this.factory = factory;
    }

    @Override
    public List<LiveRunner> liveRunners(String deployment) {
        try (var ec2 = factory.ec2()) {
            return new Ec2ProvisioningService(ec2).listRunningBenchmarkInstances(deployment);
        }
    }

    @Override
    public Map<String, String> stackParameters(String stack) {
        try (var cf = factory.cloudFormation()) {
            return new CloudFormationService(cf).getStackParameters(stack);
        }
    }

    @Override
    public void emptyBucket(String bucket) {
        try (var s3 = factory.s3()) {
            new S3UploadService(s3).deleteAllObjects(bucket);
        }
    }

    @Override
    public void deleteStack(String stack) {
        try (var cf = factory.cloudFormation()) {
            new CloudFormationService(cf).deleteStack(stack);
        }
    }

    @Override
    public List<String> retireImage(String pointer, String recipe) {
        try (var imageBuilder = factory.imageBuilder(); var ec2 = factory.ec2(); var ssm = factory.ssm()) {
            return new ImageBuilderService(imageBuilder, ec2, ssm).retireDeployment(pointer, recipe);
        }
    }
}
