package pl.wsztajerowski.baas.commands.admin.setup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.baas.infra.RunnerImageExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Step 11: store what the run proved, then say what to do next — every command with
 * {@code --deployment}, so it stays valid once a second deployment exists — and, after an
 * interactive run that changed something, how to repeat it without prompts.
 */
final class DoneStep implements SetupStep {

    static final String NAME = "done";
    static final String EXTENSION_STARTER_FILE = "runner-image-extension.yaml";
    private static final Logger logger = LoggerFactory.getLogger(DoneStep.class);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Outcome run(SetupContext context) {
        storeProfile(context);
        save(context);
        // Beside deployments/, never in it: everything in deployments/ is read as a deployment.
        writeExtensionStarter(context.configs.root().resolve(EXTENSION_STARTER_FILE), context.deployment);
        logger.info(nextSteps(context));
        if (context.interactive() && context.changedAnything()) {
            logger.info("To repeat this without prompts:\n  {}", RepeatCommand.render(context));
        }
        return SetupStep.next();
    }

    /**
     * The deployer profile is stored only on evidence that it can deploy: a permission check that
     * found nothing missing, or a stack operation that changed something. An identity that may not
     * simulate, re-running setup on a stack with nothing to change, "succeeded" while proving
     * nothing — and used to replace a working profile that way.
     */
    static void storeProfile(SetupContext context) {
        if (context.profile == null || context.profile.equals(context.config.getAws().getDeployerProfile())) {
            return;
        }
        boolean evidence = context.preflight instanceof SetupAws.Preflight.Met || context.created || context.stackChanged;
        if (evidence) {
            context.config.getAws().setDeployerProfile(context.profile);
        } else {
            logger.warn("Deployer profile {} was not stored: could not verify this identity can deploy "
                    + "(the permission check could not run, and nothing changed).{}", context.profile,
                context.config.getAws().getDeployerProfile() == null ? ""
                    : " The stored profile " + context.config.getAws().getDeployerProfile() + " stays.");
        }
    }

    /**
     * Writes the deployment's file: the region always, the deployer profile once proven. Called on
     * create as soon as CloudFormation accepts the request, and again at the end.
     */
    static void save(SetupContext context) {
        context.config.getAws().setRegion(context.region);
        if (!context.fileWritten && context.preflight instanceof SetupAws.Preflight.Met && context.profile != null) {
            context.config.getAws().setDeployerProfile(context.profile);
        }
        context.configs.save(context.config);
        if (!context.fileWritten) {
            logger.info("Configuration written to {}", context.configs.fileOf(context.deployment));
        }
        context.fileWritten = true;
    }

    static String nextSteps(SetupContext context) {
        String d = context.deployment;
        String role = context.operatorRoleArn == null || context.operatorRoleArn.isEmpty()
            ? "(no OperatorRoleArn output)" : context.operatorRoleArn;
        StringBuilder text = new StringBuilder();
        text.append("Deployment ").append(d).append(context.created ? " created." : " is set up.")
            .append("\n  Operator role: ").append(role)
            .append("\n  Your deployer credentials run day-to-day commands as that role.");
        if (context.imageMissing) {
            text.append("\nBuild the runner image first (~15 minutes; `baas run` fails until it exists):")
                .append("\n  baas --deployment ").append(d).append(" admin image build");
        }
        text.append("\nRun a benchmark:")
            .append("\n  baas --deployment ").append(d)
            .append(" run --benchmark-jar <your-benchmarks.jar> --project <name> jmh -- <BenchmarkRegex>");
        text.append("\nA teammate needs two steps:")
            .append("\n  1. An IAM administrator allows their identity this statement:")
            .append("\n       {\"Effect\": \"Allow\", \"Action\": \"sts:AssumeRole\", \"Resource\": \"").append(role).append("\"}")
            .append("\n  2. On their machine:")
            .append("\n       baas --deployment ").append(d).append(" config sync --operator-aws-profile <their-profile>");
        Optional<SetupContext.Federation> federation = context.revoke
            ? Optional.empty()
            : context.federation.or(() -> context.deployedFederation);
        federation.ifPresent(f -> {
            text.append("\nGitHub Actions reads two repository variables (baas cannot check them):");
            for (String repo : f.repos()) {
                text.append("\n  gh variable set OPERATOR_ROLE_ARN --body ").append(role).append(" --repo ").append(f.org()).append('/').append(repo)
                    .append("\n  gh variable set AWS_REGION --body ").append(context.region).append(" --repo ").append(f.org()).append('/').append(repo);
            }
        });
        return text.toString();
    }

    /**
     * The operator's starting point for extending the runner image — the same document a pull of a
     * deployment without an extension prints. Never overwritten: it may hold edits, and the
     * stale-push guard makes an old copy harmless rather than something to refresh.
     */
    static void writeExtensionStarter(Path file, String deployment) {
        if (Files.exists(file)) {
            return;
        }
        try {
            Files.writeString(file, RunnerImageExtension.withMarker(""));
            logger.info("""
                Runner-image extension starter written to {}
                  Edit it, then: baas --deployment {} admin image build --extension {}""", file, deployment, file);
        } catch (IOException e) {
            // A convenience, not part of the deployment: the pull prints the same document.
            logger.warn("Could not write {}: {}. `baas --deployment {} admin image show --extension` prints the same starter.",
                file, e.getMessage(), deployment);
        }
    }
}
