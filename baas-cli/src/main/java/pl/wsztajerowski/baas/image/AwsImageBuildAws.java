package pl.wsztajerowski.baas.image;

import pl.wsztajerowski.baas.aws.AwsClientFactory;
import pl.wsztajerowski.baas.aws.CloudFormationService;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.imagebuilder.ImagebuilderClient;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.io.IOException;
import java.util.Map;

/** {@link ImageBuildAws} over the deployer's clients, held for the build and closed after it. */
final class AwsImageBuildAws implements ImageBuildAws {

    private final ImagebuilderClient imageBuilder;
    private final Ec2Client ec2;
    private final SsmClient ssm;
    private final CloudFormationClient cf;
    private final CloudFormationService cloudFormation;
    private final ImageBuilderService service;

    AwsImageBuildAws(AwsClientFactory factory) {
        this.imageBuilder = factory.imageBuilder();
        this.ec2 = factory.ec2();
        this.ssm = factory.ssm();
        this.cf = factory.cloudFormation();
        this.cloudFormation = new CloudFormationService(cf);
        this.service = new ImageBuilderService(imageBuilder, ec2, ssm);
    }

    @Override
    public boolean stackExists(String stack) {
        return cloudFormation.stackExists(stack);
    }

    @Override
    public Map<String, String> stackParameters(String stack) {
        return cloudFormation.getStackParameters(stack);
    }

    @Override
    public Map<String, String> stackOutputs(String stack) {
        return cloudFormation.getStackOutputs(stack);
    }

    @Override
    public String resolveParent(String amiName, String region) {
        return new ParentImageResolver(ec2).resolve(amiName, region);
    }

    @Override
    public void preflightVersion(String component, String version, String document) {
        service.preflightVersion(component, version, document);
    }

    @Override
    public void updateStack(String stack, Map<String, String> imageParameters) throws IOException {
        cloudFormation.updateStackParameters(stack, CloudFormationService.coreTemplate(), imageParameters);
    }

    @Override
    public String publish(String pipelineArn, String pointer, String label, String parentAmiId, String recipeVersion)
            throws InterruptedException {
        return service.publish(pipelineArn, pointer, label, parentAmiId, recipeVersion);
    }

    @Override
    public void close() {
        imageBuilder.close();
        ec2.close();
        ssm.close();
        cf.close();
    }
}
