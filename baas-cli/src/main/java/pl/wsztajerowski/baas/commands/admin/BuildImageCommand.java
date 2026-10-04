package pl.wsztajerowski.baas.commands.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;
import pl.wsztajerowski.baas.BaasApp;
import pl.wsztajerowski.baas.LoggingMixin;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.config.ConfigService;
import pl.wsztajerowski.baas.infra.AwsClientFactory;
import pl.wsztajerowski.baas.infra.CloudFormationService;
import pl.wsztajerowski.baas.infra.ImageBuilderService;
import pl.wsztajerowski.baas.infra.ParentImageResolver;
import pl.wsztajerowski.baas.infra.RunnerImageExtension;
import pl.wsztajerowski.baas.infra.RunnerImageParameters;
import pl.wsztajerowski.baas.infra.RunnerImageRenderer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

@Command(
    name = "build-image",
    mixinStandardHelpOptions = true,
    description = "Build the runner AMI — the bundled base, the installation's extension, the BaaS contract — and publish it.",
    footer = {
        "",
        "Takes ~15 minutes. Without --extension the deployed extension is kept as it is.",
        "To change it:  baas admin image --extension > ext.yaml, edit, then",
        "               baas admin build-image --extension ext.yaml",
        "A file pulled before someone else pushed is refused rather than allowed to discard",
        "their extension."
    }
)
public class BuildImageCommand implements Callable<Integer> {

    /**
     * Why this CLI must not build, or {@code null} when it may. A CLI bundling an older base than
     * the installation's would submit that base and replace the newer component — the older version
     * no longer exists once replaced, so nothing else refuses it — silently moving every later
     * result onto the older environment. There is no override: rebuilding a historical base is
     * done from a checkout of that commit, as re-measuring a past environment always was.
     */
    static String olderBaseRefusal(String bundledBase, String deployedBase, String installation) {
        if (deployedBase == null || RunnerImageParameters.compareVersions(bundledBase, deployedBase) >= 0) {
            return null;
        }
        return """
            This CLI bundles runner-image base %s, but installation %s is built on %s.
              Building would move every later result onto the older environment.
              Upgrade the CLI first:  ~/.local/share/baas/install.sh --update
            Nothing was changed.""".formatted(bundledBase, installation, deployedBase);
    }

    private static final Logger logger = LoggerFactory.getLogger(BuildImageCommand.class);

    @Mixin LoggingMixin loggingMixin;

    @Option(names = "--extension", paramLabel = "<file>",
        description = "Replace the installation's runner-image extension with this AWSTOE document. "
            + "A file of comments only removes it.")
    Path extensionFile;

    @Spec CommandSpec spec;

    private ConfigService configService() {
        return BaasApp.configService(spec);
    }

    @Override
    public Integer call() throws Exception {
        BaasConfig config = configService().load();

        String prefix = config.requirePrefix();
        String region = config.getAws().resolveRegion();
        var renderer = new RunnerImageRenderer();
        var definition = renderer.definition();
        String baseVersion = definition.imageVersion();
        String base = renderer.renderBase();

        // Read before any AWS call: a file that cannot be pushed fails without a round trip.
        Optional<RunnerImageExtension.WorkingCopy> pushed = Optional.empty();
        if (extensionFile != null) {
            var file = RunnerImageExtension.parse(Files.readString(extensionFile));
            try {
                RunnerImageExtension.requireStorable(file.content());
            } catch (IllegalStateException e) {
                logger.error(e.getMessage());
                return 1;
            }
            pushed = Optional.of(file);
        }

        // Deployer credentials, like every other `baas admin` subcommand: building an image needs
        // imagebuilder:*, ssm:PutParameter and a widened iam:PassRole, none of which an operator
        // holds. See RunCommand for the other half of that split.
        var factory = new AwsClientFactory(region, config.getAws().getProfile());

        String componentName = prefix + "-component-runner";
        try (var imageBuilderClient = factory.imageBuilder();
             var ec2 = factory.ec2();
             var ssm = factory.ssm();
             var cf = factory.cloudFormation()) {

            var cloudFormation = new CloudFormationService(cf);
            if (!cloudFormation.stackExists(prefix)) {
                logger.error("Stack {} does not exist. Run `baas admin setup` first.", prefix);
                return 1;
            }
            Map<String, String> deployed = cloudFormation.getStackParameters(prefix);
            String refusal = olderBaseRefusal(baseVersion, deployed.get(RunnerImageParameters.IMAGE_VERSION), prefix);
            if (refusal != null) {
                logger.error(refusal);
                return 1;
            }
            String deployedExtension = deployed.getOrDefault(RunnerImageParameters.EXTENSION_DATA, "");

            String extension = deployedExtension;
            String parentAmiId;
            try {
                if (pushed.isPresent()) {
                    RunnerImageExtension.requireCurrent(pushed.get(), deployedExtension);
                    extension = pushed.get().content();
                }
                parentAmiId = new ParentImageResolver(ec2).resolve(definition.parentImage().amiName(), region);
            } catch (IllegalStateException e) {
                logger.error(e.getMessage());
                return 1;
            }

            var plan = RunnerImageParameters.plan(renderer, baseVersion, base, parentAmiId, extension, deployed);
            var service = new ImageBuilderService(imageBuilderClient, ec2, ssm);

            // Before the stack update, not after: a rejected component version costs a minute of
            // CloudFormation rollback, and the message names the resource rather than the fix. Only
            // the base is checked — its version is the one written by hand; the others are derived
            // from the deployed stack and cannot collide.
            try {
                service.preflightVersion(componentName, baseVersion, base);
            } catch (IllegalStateException e) {
                logger.error(e.getMessage());
                return 1;
            }

            logger.info("Registering runner image {} (parent {})...", plan.label(), parentAmiId);
            // Every image parameter is sent; everything else — the networking choices in
            // particular — is carried forward from the deployed stack.
            cloudFormation.updateStackParameters(prefix, CloudFormationService.coreTemplate(), plan.values());

            String pipelineArn = cloudFormation.getStackOutputs(prefix).get("RunnerImagePipelineArn");
            if (pipelineArn == null || pipelineArn.isEmpty()) {
                logger.error("Stack {} has no RunnerImagePipelineArn output. Run `baas admin setup` first.",
                    prefix);
                return 1;
            }

            String parameterName = "/" + prefix + "/runner/ami-id";
            String amiId = service.publish(pipelineArn, parameterName, plan.label(), parentAmiId);

            logger.info("""
                Runner image {} built and published.
                  AMI:       {}
                  Parent:    {}
                  Extension: {}
                  Pointer:   {}""", plan.label(), amiId, parentAmiId,
                RunnerImageExtension.identity(extension), parameterName);
            return 0;
        }
    }
}
