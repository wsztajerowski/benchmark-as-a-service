package pl.wsztajerowski.baas.commands.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import pl.wsztajerowski.baas.LoggingMixin;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.config.ConfigService;
import pl.wsztajerowski.baas.infra.AwsClientFactory;
import pl.wsztajerowski.baas.infra.CloudFormationService;
import pl.wsztajerowski.baas.infra.ImageBuilderService;
import pl.wsztajerowski.baas.infra.RunnerImageRenderer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

@Command(
    name = "build-image",
    mixinStandardHelpOptions = true,
    description = "Build the runner AMI from infra/runner-image.yaml and publish it.",
    footer = {
        "",
        "Takes ~15 minutes. Bump imageVersion in infra/runner-image.yaml whenever you",
        "change a tool version — an Image Builder component is immutable at a version,",
        "and this command refuses to start a build the stack would reject."
    }
)
public class BuildImageCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(BuildImageCommand.class);

    @Mixin LoggingMixin loggingMixin;

    @Option(names = "--aws-profile", description = "AWS CLI profile (deployer credentials).")
    String awsProfile;

    private final ConfigService configService = new ConfigService();

    @Override
    public Integer call() throws Exception {
        BaasConfig config = configService.load();
        if (awsProfile != null) config.getAws().setProfile(awsProfile);

        String prefix = config.getPrefix();
        var renderer = new RunnerImageRenderer();
        var definition = renderer.definition();
        String imageVersion = definition.imageVersion();
        String parentAmiId = definition.parentImage().amiId();

        if (!config.getAws().getRegion().equals(definition.parentImage().region())) {
            // An AMI ID means nothing outside its own region, so this would fail deep inside the
            // stack update with a message about an image that "does not exist".
            logger.error("""
                    infra/runner-image.yaml pins a parent AMI in {}, but this stack is in {}.
                    Set parentImage.region and parentImage.amiId to an AL2023 image in {}.""",
                definition.parentImage().region(), config.getAws().getRegion(), config.getAws().getRegion());
            return 1;
        }

        // Deployer credentials, like every other `baas admin` subcommand: building an image needs
        // imagebuilder:*, ssm:PutParameter and a widened iam:PassRole, none of which an operator
        // holds. See RunCommand for the other half of that split.
        var factory = new AwsClientFactory(config.getAws().getRegion(), config.getAws().getProfile());

        String componentName = prefix + "-runner-toolchain";
        try (var imageBuilderClient = factory.imageBuilder();
             var ec2 = factory.ec2();
             var ssm = factory.ssm()) {

            var service = new ImageBuilderService(imageBuilderClient, ec2, ssm);

            // Before the stack update, not after: a rejected component version costs a minute of
            // CloudFormation rollback, and the message names the resource rather than the fix.
            try {
                service.preflightVersion(componentName, imageVersion, renderer.renderComponent());
            } catch (IllegalStateException e) {
                logger.error(e.getMessage());
                return 1;
            }

            logger.info("Registering runner image {} (parent {})...", imageVersion, parentAmiId);
            updateStack(factory, config, renderer);

            String pipelineArn;
            try (var cf = factory.cloudFormation()) {
                var outputs = new CloudFormationService(cf).getStackOutputs(config.getAws().getCoreStackName());
                pipelineArn = outputs.get("RunnerImagePipelineArn");
                if (pipelineArn == null || pipelineArn.isEmpty()) {
                    logger.error("Stack {} has no RunnerImagePipelineArn output. Run `baas admin setup` first.",
                        config.getAws().getCoreStackName());
                    return 1;
                }
            }

            String parameterName = "/" + prefix + "/runner/ami-id";
            String amiId = service.publish(pipelineArn, parameterName, imageVersion, parentAmiId);

            logger.info("""
                Runner image {} built and published.
                  AMI:     {}
                  Parent:  {}
                  Pointer: {}""", imageVersion, amiId, parentAmiId, parameterName);
            return 0;
        }
    }

    /**
     * Re-submits the core template carrying the freshly rendered component and version. The image
     * definition reaches the stack only through these parameters, so a build without this update
     * would bake the previously registered recipe.
     *
     * <p>Only the three image parameters are sent; everything else — the networking choices in
     * particular — is carried forward from the deployed stack.
     */
    private void updateStack(AwsClientFactory factory, BaasConfig config, RunnerImageRenderer renderer)
        throws IOException {
        try (var cf = factory.cloudFormation()) {
            new CloudFormationService(cf).updateStackParameters(
                config.getAws().getCoreStackName(), loadTemplate(), renderer.stackParameters());
        }
    }

    private String loadTemplate() throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/templates/cf-template-core.yaml")) {
            if (is == null) throw new IllegalStateException("CF template not found in classpath");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
