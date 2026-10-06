package pl.wsztajerowski.baas.commands.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import pl.wsztajerowski.baas.BaasApp;
import pl.wsztajerowski.baas.LoggingMixin;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.config.ConfigService;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.infra.AwsClientFactory;
import pl.wsztajerowski.baas.infra.CloudFormationService;
import pl.wsztajerowski.baas.infra.ImageBuilderService;
import pl.wsztajerowski.baas.infra.RunnerImageExtension;
import pl.wsztajerowski.baas.infra.RunnerImageParameters;
import pl.wsztajerowski.baas.infra.RunnerImageRenderer;

import java.util.concurrent.Callable;

@Command(
    name = "image",
    mixinStandardHelpOptions = true,
    description = "Report the runner image currently published for this account, and its extension."
)
public class ImageCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(ImageCommand.class);

    @Mixin LoggingMixin loggingMixin;

    @Option(names = "--extension",
        description = "Print the deployment's runner-image extension, ready to edit and push with "
            + "`baas admin build-image --extension`. Prints the starter when there is none.")
    boolean printExtension;

    @Spec CommandSpec spec;


    private ConfigService configService() {
        return BaasApp.configService(spec);
    }

    @Override
    public Integer call() {
        BaasConfig config = configService().load();
        String prefix = config.requirePrefix();

        // Deployer credentials, consistent with every other `baas admin` subcommand.
        var factory = new AwsClientFactory(config.getAws().resolveRegion(), config.getAws().getProfile());
        String parameterName = config.amiParameterPath();
        var console = Console.of(spec.commandLine().getOut());

        String extension;
        try (var cf = factory.cloudFormation()) {
            var cloudFormation = new CloudFormationService(cf);
            if (!cloudFormation.stackExists(prefix)) {
                logger.error("Stack {} does not exist. Run `baas admin setup` first.", prefix);
                return 1;
            }
            extension = cloudFormation.getStackParameters(prefix)
                .getOrDefault(RunnerImageParameters.EXTENSION_DATA, "");
        }

        if (printExtension) {
            // Payload, verbatim: this is the working copy a push reads back, marker line included.
            console.print(RunnerImageExtension.withMarker(extension));
            return 0;
        }

        try (var imageBuilder = factory.imageBuilder(); var ec2 = factory.ec2(); var ssm = factory.ssm()) {
            var current = new ImageBuilderService(imageBuilder, ec2, ssm).currentImage(parameterName);

            if (current.isEmpty()) {
                // Warn, not payload: an absent image means every `baas run` fails until one is
                // built. Exit 0 still — the command answered the question it was asked.
                logger.warn("""
                    No runner image has been built for this account.
                      Build one:  baas admin build-image
                    Until then `baas run` will fail before launching anything.""");
                return 0;
            }

            var image = current.get();
            console.printf("""
                Runner image
                  Version:     %s
                  AMI:         %s
                  Parent AMI:  %s
                  Built:       %s
                  Pointer:     %s
                """,
                orUnknown(image.imageVersion()), image.amiId(), orUnknown(image.parentAmiId()),
                orUnknown(image.createdAt()), parameterName);
            printExtension(console, extension, image.imageVersion());

            // The base this CLI bundles is not necessarily the one deployed, and a difference
            // between them is the usual reason a result carries an unexpected imageVersion tag.
            String bundled = new RunnerImageRenderer().definition().imageVersion();
            String warning = driftWarning(bundled, baseOf(image.imageVersion()));
            if (warning != null) {
                logger.warn("{}", warning);
            }
            return 0;
        }
    }

    /**
     * The hash is opaque, so the step names carry the readable part. The stack's extension is what
     * the next build will bake; when a push's build failed it differs from the published image's.
     */
    static void printExtension(Console console, String extension, String label) {
        if (extension.isEmpty()) {
            console.println("  Extension:   none");
            return;
        }
        String hash = RunnerImageExtension.hash(extension);
        int size = extension.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        boolean published = label != null && label.endsWith("+ext." + hash);
        console.printf("  Extension:   %s, %d bytes of %d%s%n", hash, size, RunnerImageExtension.LIMIT_BYTES,
            published ? "" : " (deployed; not in the published image yet)");
        var steps = RunnerImageExtension.stepsByPhase(extension);
        if (steps.isEmpty()) {
            console.println("                 (not a parseable AWSTOE document)");
        }
        steps.forEach((phase, names) ->
            console.printf("                 %s: %s%n", phase, String.join(", ", names)));
    }

    /** {@code 1.3.0} from {@code 1.3.0+ext.3f9a1c2e}; null for an image with no version tag. */
    /**
     * What to do about a bundled base that differs from the published one, by direction. Newer:
     * build it. Older: upgrading the CLI is the fix — build-image refuses an older base, and saying
     * "build" here once advised exactly the downgrade it now refuses.
     */
    static String driftWarning(String bundledBase, String deployedBase) {
        if (deployedBase == null || bundledBase.equals(deployedBase)) {
            return null;
        }
        if (RunnerImageParameters.compareVersions(bundledBase, deployedBase) > 0) {
            return "This CLI bundles runner-image base " + bundledBase + ", but the published image is built on "
                + deployedBase + " — run `baas admin build-image` to publish it.";
        }
        return "This CLI bundles runner-image base " + bundledBase + ", older than the published image's "
            + deployedBase + " — upgrade the CLI (~/.local/share/baas/install.sh --update); build-image refuses an older base.";
    }

    static String baseOf(String label) {
        if (label == null || label.isEmpty()) {
            return null;
        }
        int plus = label.indexOf('+');
        return plus < 0 ? label : label.substring(0, plus);
    }

    /** An image built before the identity tags existed reports null rather than a blank column. */
    private static String orUnknown(String value) {
        return value != null && !value.isEmpty() ? value : "(unknown)";
    }
}
