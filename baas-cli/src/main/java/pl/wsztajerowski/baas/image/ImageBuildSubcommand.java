package pl.wsztajerowski.baas.image;

import pl.wsztajerowski.baas.cli.CommandRefusal;
import pl.wsztajerowski.baas.cli.Invocation;
import pl.wsztajerowski.baas.session.DeployerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;
import pl.wsztajerowski.baas.cli.LoggingMixin;
import pl.wsztajerowski.baas.session.BaasConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

@Command(
    name = "build",
    mixinStandardHelpOptions = true,
    description = "Build the runner AMI — the bundled base, the deployment's extension, the BaaS contract — and publish it.",
    footer = {
        "",
        "Takes ~15 minutes. Without --extension the deployed extension is kept as it is.",
        "To change it:  baas admin image show --extension > ext.yaml, edit, then",
        "               baas admin image build --extension ext.yaml",
        "A file pulled before someone else pushed is refused rather than allowed to discard",
        "their extension."
    }
)
public class ImageBuildSubcommand implements Callable<Integer> {


    private static final Logger logger = LoggerFactory.getLogger(ImageBuildSubcommand.class);

    @Mixin LoggingMixin loggingMixin;

    @Option(names = "--extension", paramLabel = "<file>",
        description = "Replace the deployment's runner-image extension with this AWSTOE document. "
            + "A file of comments only removes it.")
    Path extensionFile;

    @Spec CommandSpec spec;


    @Override
    public Integer call() throws Exception {
        BaasConfig config = Invocation.configService(spec).load();

        String prefix = config.requirePrefix();
        String region = config.getAws().resolveRegion();
        // Read before any AWS call: a file that cannot be pushed fails without a round trip.
        Optional<RunnerImageExtension.WorkingCopy> pushed = Optional.empty();
        if (extensionFile != null) {
            if (!Files.isRegularFile(extensionFile)) {
                // Files.readString's NoSuchFileException carries only the path as its message.
                throw CommandRefusal.refused("Extension file not found: " + extensionFile + ". Nothing was changed.");
            }
            var file = RunnerImageExtension.parse(Files.readString(extensionFile));
            try {
                RunnerImageExtension.requireStorable(file.content());
            } catch (IllegalStateException e) {
                throw CommandRefusal.refused(e.getMessage());
            }
            pushed = Optional.of(file);
        }

        // Deployer credentials, like every other `baas admin` subcommand: building an image needs
        // imagebuilder:*, ssm:PutParameter and a widened iam:PassRole, none of which an operator
        // holds. See RunCommand for the other half of that split.
        var factory = DeployerSession.factory(config);

        RunnerImageBuild.Result result;
        try {
            result = RunnerImageBuild.run(factory, prefix, region, pushed);
        } catch (IllegalStateException e) {
            throw CommandRefusal.refused(e.getMessage());
        }
        logger.info("""
            Runner image {} built and published.
              AMI:       {}
              Parent:    {}
              Extension: {}
              Pointer:   {}""", result.label(), result.amiId(), result.parentAmiId(),
            RunnerImageExtension.identity(result.extension()), result.pointer());
        return 0;
    }
}
