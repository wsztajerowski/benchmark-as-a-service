package pl.wsztajerowski.baas.image;

import pl.wsztajerowski.baas.session.CoreStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.baas.session.DeploymentNames;
import pl.wsztajerowski.baas.aws.AwsClientFactory;
import pl.wsztajerowski.baas.aws.CloudFormationService;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * One runner-image build of a deployment: register the image in the stack, then build or adopt it
 * and publish it. {@code baas admin image build} and setup's image step run exactly this, so the two
 * cannot build differently.
 */
public final class RunnerImageBuild {

    private static final Logger logger = LoggerFactory.getLogger(RunnerImageBuild.class);

    /** What was published. */
    public record Result(String amiId, String label, String parentAmiId, String extension, String pointer) {}

    private RunnerImageBuild() {}

    /**
     * @param pushed an extension to replace the deployed one with, or empty to carry it forward
     * @throws IllegalStateException with a message for the user when the build is refused or fails
     */
    public static Result run(AwsClientFactory factory, String prefix, String region,
                             Optional<RunnerImageExtension.WorkingCopy> pushed)
            throws IOException, InterruptedException {
        var renderer = new RunnerImageRenderer();
        var definition = renderer.definition();
        String baseVersion = definition.imageVersion();
        String base = renderer.renderBase();
        String componentName = DeploymentNames.of(prefix).runnerComponent();

        try (var imageBuilderClient = factory.imageBuilder();
             var ec2 = factory.ec2();
             var ssm = factory.ssm();
             var cf = factory.cloudFormation()) {

            var cloudFormation = new CloudFormationService(cf);
            if (!cloudFormation.stackExists(prefix)) {
                throw new IllegalStateException("Stack " + prefix
                    + " does not exist. Run `baas --deployment " + prefix + " admin deployment setup` first.");
            }
            Map<String, String> deployed = cloudFormation.getStackParameters(prefix);
            String refusal = olderBaseRefusal(baseVersion,
                deployed.get(RunnerImageParameters.IMAGE_VERSION), prefix);
            if (refusal != null) {
                throw new IllegalStateException(refusal);
            }
            String deployedExtension = deployed.getOrDefault(RunnerImageParameters.EXTENSION_DATA, "");

            String extension = deployedExtension;
            if (pushed.isPresent()) {
                RunnerImageExtension.requireCurrent(pushed.get(), deployedExtension);
                extension = pushed.get().content();
            }
            String parentAmiId = new ParentImageResolver(ec2).resolve(definition.parentImage().amiName(), region);

            var plan = RunnerImageParameters.plan(renderer, baseVersion, base, parentAmiId, extension, deployed);
            var service = new ImageBuilderService(imageBuilderClient, ec2, ssm);

            // Before the stack update, not after: a rejected component version costs a minute of
            // CloudFormation rollback, and the message names the resource rather than the fix. Only
            // the base is checked — its version is the one written by hand; the others are derived
            // from the deployed stack and cannot collide.
            service.preflightVersion(componentName, baseVersion, base);

            logger.info("Registering runner image {} (parent {})...", plan.label(), parentAmiId);
            // Every image parameter is sent; everything else — the networking choices in
            // particular — is carried forward from the deployed stack.
            cloudFormation.updateStackParameters(prefix, CloudFormationService.coreTemplate(), plan.values());

            String pipelineArn = cloudFormation.getStackOutputs(prefix).get(CoreStack.OUTPUT_RUNNER_IMAGE_PIPELINE_ARN);
            if (pipelineArn == null || pipelineArn.isEmpty()) {
                throw new IllegalStateException("Stack " + prefix + " has no RunnerImagePipelineArn output. Run `baas --deployment "
                    + prefix + " admin deployment setup` first.");
            }

            String parameterName = DeploymentNames.of(prefix).amiPointer();
            String amiId = service.publish(pipelineArn, parameterName, plan.label(), parentAmiId,
                plan.values().get(RunnerImageParameters.RECIPE_VERSION));
            return new Result(amiId, plan.label(), parentAmiId, extension, parameterName);
        }
    }

    /**
     * Why this CLI must not build, or {@code null} when it may. A CLI bundling an older base than
     * the deployment's would submit that base and replace the newer component — the older version
     * no longer exists once replaced, so nothing else refuses it — silently moving every later
     * result onto the older environment. There is no override: rebuilding a historical base is
     * done from a checkout of that commit, as re-measuring a past environment always was.
     */
    static String olderBaseRefusal(String bundledBase, String deployedBase, String deployment) {
        if (deployedBase == null || RunnerImageParameters.compareVersions(bundledBase, deployedBase) >= 0) {
            return null;
        }
        return """
            This CLI bundles runner-image base %s, but deployment %s is built on %s.
              Building would move every later result onto the older environment.
              Upgrade the CLI first:  ~/.local/share/baas/install.sh --update
            Nothing was changed.""".formatted(bundledBase, deployment, deployedBase);
    }
}
