package pl.wsztajerowski.baas.image;

import pl.wsztajerowski.baas.session.CoreStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.baas.session.BaasVersion;
import pl.wsztajerowski.baas.session.CliVersionCheck;
import pl.wsztajerowski.baas.session.DeploymentNames;
import pl.wsztajerowski.baas.aws.AwsClientFactory;

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
        try (var aws = new AwsImageBuildAws(factory)) {
            return run(aws, prefix, region, pushed);
        }
    }

    static Result run(ImageBuildAws aws, String prefix, String region,
                      Optional<RunnerImageExtension.WorkingCopy> pushed)
            throws IOException, InterruptedException {
        var renderer = new RunnerImageRenderer();
        var definition = renderer.definition();
        String baseVersion = definition.imageVersion();
        String base = renderer.renderBase();
        String componentName = DeploymentNames.of(prefix).runnerComponent();

        if (!aws.stackExists(prefix)) {
            throw new IllegalStateException("Stack " + prefix
                + " does not exist. Run `baas --deployment " + prefix + " admin deployment setup` first.");
        }
        Map<String, String> deployed = aws.stackParameters(prefix);
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
        String parentAmiId = aws.resolveParent(definition.parentImage().amiName(), region);

        var plan = RunnerImageParameters.plan(renderer, DeploymentNames.of(prefix), region, baseVersion, base,
            parentAmiId, extension, deployed);

        // Before the stack update, not after: a rejected component version costs a minute of
        // CloudFormation rollback, and the message names the resource rather than the fix. Only
        // the base is checked — its version is the one written by hand; the others are derived
        // from the deployed stack and cannot collide.
        aws.preflightVersion(componentName, baseVersion, base);

        logger.info("Registering runner image {} (parent {})...", plan.label(), parentAmiId);
        // Every image parameter is sent; everything else — the networking choices in
        // particular — is carried forward from the deployed stack.
        aws.updateStack(prefix, plan.values());

        String pipelineArn = aws.stackOutputs(prefix).get(CoreStack.OUTPUT_RUNNER_IMAGE_PIPELINE_ARN);
        if (pipelineArn == null || pipelineArn.isEmpty()) {
            throw new IllegalStateException("Stack " + prefix + " has no RunnerImagePipelineArn output. Run `baas --deployment "
                + prefix + " admin deployment setup` first.");
        }

        String parameterName = DeploymentNames.of(prefix).amiPointer();
        String amiId = aws.publish(pipelineArn, parameterName, plan.label(), parentAmiId,
            plan.values().get(RunnerImageParameters.RECIPE_VERSION), DeploymentNames.of(prefix).bucket());
        return new Result(amiId, plan.label(), parentAmiId, extension, parameterName);
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
              Upgrade the CLI first:  %s
            Nothing was changed.""".formatted(bundledBase, deployment, deployedBase,
            CliVersionCheck.installCommand(BaasVersion.sourceRepo()));
    }
}
