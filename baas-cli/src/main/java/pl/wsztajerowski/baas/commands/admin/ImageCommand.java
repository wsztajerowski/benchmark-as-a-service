package pl.wsztajerowski.baas.commands.admin;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import pl.wsztajerowski.baas.LoggingMixin;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.config.ConfigService;
import pl.wsztajerowski.baas.infra.AwsClientFactory;
import pl.wsztajerowski.baas.infra.ImageBuilderService;
import pl.wsztajerowski.baas.infra.RunnerImageRenderer;

import java.util.concurrent.Callable;

@Command(
    name = "image",
    mixinStandardHelpOptions = true,
    description = "Report the runner image currently published for this account."
)
public class ImageCommand implements Callable<Integer> {

    @Mixin LoggingMixin loggingMixin;

    @Option(names = "--aws-profile", description = "AWS CLI profile (deployer credentials).")
    String awsProfile;

    private final ConfigService configService = new ConfigService();

    @Override
    public Integer call() {
        BaasConfig config = configService.load();
        if (awsProfile != null) config.getAws().setProfile(awsProfile);

        // Deployer credentials, consistent with every other `baas admin` subcommand.
        var factory = new AwsClientFactory(config.getAws().getRegion(), config.getAws().getProfile());
        String parameterName = "/" + config.getPrefix() + "/runner/ami-id";

        try (var imageBuilder = factory.imageBuilder(); var ec2 = factory.ec2(); var ssm = factory.ssm()) {
            var current = new ImageBuilderService(imageBuilder, ec2, ssm).currentImage(parameterName);

            if (current.isEmpty()) {
                // Command payload, so stdout rather than the logger — this is what the user asked
                // for, not a diagnostic about producing it.
                System.out.println("""
                    No runner image has been built for this account.
                      Build one:  baas admin build-image
                    Until then `baas run` will fail before launching anything.""");
                return 0;
            }

            var image = current.get();
            String declared = new RunnerImageRenderer().definition().imageVersion();

            System.out.printf("""
                Runner image
                  Version:     %s
                  AMI:         %s
                  Parent AMI:  %s
                  Built:       %s
                  Pointer:     %s
                """,
                orUnknown(image.imageVersion()), image.amiId(), orUnknown(image.parentAmiId()),
                orUnknown(image.createdAt()), parameterName);

            // The declaration in the working tree is not necessarily what is deployed, and a diff
            // between them is the usual reason a result carries an unexpected imageVersion tag.
            if (image.imageVersion() != null && !declared.equals(image.imageVersion())) {
                System.out.printf(
                    "%ninfra/runner-image.yaml declares %s — run `baas admin build-image` to publish it.%n",
                    declared);
            }
            return 0;
        }
    }

    /** An image built before the identity tags existed reports null rather than a blank column. */
    private static String orUnknown(String value) {
        return value != null && !value.isEmpty() ? value : "(unknown)";
    }
}
