package pl.wsztajerowski.baas.image;

import pl.wsztajerowski.baas.cli.CommandContext;
import pl.wsztajerowski.baas.cli.CommandRefusal;
import pl.wsztajerowski.baas.cli.Invocation;
import pl.wsztajerowski.baas.session.BaasVersion;
import pl.wsztajerowski.baas.session.CliVersionCheck;
import pl.wsztajerowski.baas.session.DeployerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import pl.wsztajerowski.baas.cli.LoggingMixin;
import pl.wsztajerowski.baas.console.Hints;
import pl.wsztajerowski.baas.session.BaasConfig;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.aws.CloudFormationService;

import com.fasterxml.jackson.databind.ObjectMapper;
import pl.wsztajerowski.baas.aws.S3UploadService;
import pl.wsztajerowski.baas.console.Json;
import pl.wsztajerowski.baas.console.Printable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Command(
    name = "show",
    mixinStandardHelpOptions = true,
    description = "Report the runner image currently published for this deployment, and its extension."
)
public class ImageShowSubcommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(ImageShowSubcommand.class);

    @Mixin LoggingMixin loggingMixin;

    @Option(names = "--extension",
        description = "Print the deployment's runner-image extension, ready to edit and push with "
            + "`baas admin image build --extension`. Prints the starter when there is none.")
    boolean printExtension;

    @Option(names = "--format", description = "Output format: text (default), json.", defaultValue = "text")
    String format = "text";

    @Spec CommandSpec spec;

    @Mixin CommandContext context = new CommandContext();

    @Override
    public Integer call() {
        CommandRefusal.requireFormat(format, List.of("text", "json"));
        boolean json = "json".equalsIgnoreCase(format);
        if (json && printExtension) {
            throw CommandRefusal.usage("--extension prints the extension document itself; it has no JSON form.");
        }
        BaasConfig config = Invocation.configService(spec).load();
        String prefix = config.requirePrefix();

        // Deployer credentials, consistent with every other `baas admin` subcommand.
        var factory = DeployerSession.factory(config);
        String parameterName = config.amiParameterPath();
        var console = context.console();

        String extension;
        try (var cf = factory.cloudFormation()) {
            var cloudFormation = new CloudFormationService(cf);
            if (!cloudFormation.stackExists(prefix)) {
                throw CommandRefusal.refused("Stack " + prefix + " does not exist. Run `"
                    + Hints.command(prefix, "admin deployment setup") + "` first.");
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
                if (json) {
                    console.println(json(prefix, parameterName, null, extension, Optional.empty(), bundledBase()));
                }
                // Warn, not payload: an absent image means every `baas run` fails until one is
                // built. Exit 0 still — the command answered the question it was asked.
                logger.warn("""
                    Deployment {} has no runner image yet.
                      Build one:  {}
                    Until then `baas run` will fail before launching anything.""",
                    prefix, Hints.command(prefix, "admin image build"));
                return 0;
            }

            var image = current.get();
            Optional<String> jdkList = Optional.empty();
            if (image.selectsJdks() && image.imageVersion() != null) {
                try (var s3 = factory.s3()) {
                    jdkList = new S3UploadService(s3).getObjectIfExists(config.names().bucket(),
                        RunnerImageRenderer.jdkListKey(image.imageVersion()));
                }
            }
            if (json) {
                console.println(json(prefix, parameterName, image, extension, jdkList, bundledBase()));
                return 0;
            }
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
            printJdks(console, image, jdkList);

            // The base this CLI bundles is not necessarily the one deployed, and a difference
            // between them is the usual reason a result carries an unexpected imageVersion tag.
            String bundled = bundledBase();
            String warning = driftWarning(prefix, bundled, baseOf(image.imageVersion()));
            if (warning != null) {
                logger.warn("{}", warning);
            }
            return 0;
        }
    }

    private static String bundledBase() {
        return new RunnerImageRenderer().definition().imageVersion();
    }

    /**
     * {@code --format json} (G12): what the text shows, for an admin script. {@code image} is null
     * when none is published, {@code extension} null when the stack holds none; {@code jdks} comes
     * from the image's recorded list, or its tag alone when the list is gone.
     */
    static String json(String deployment, String pointer, RunnerImage image, String extension,
                       Optional<String> jdkList, String bundledBase) {
        String imageJson = image == null ? "null"
            : "{\"version\":" + Json.string(image.imageVersion()) + ",\"amiId\":" + Json.string(image.amiId())
                + ",\"parentAmiId\":" + Json.string(image.parentAmiId())
                + ",\"createdAt\":" + Json.string(image.createdAt()) + "}";
        String extensionJson = "null";
        if (!extension.isEmpty()) {
            String hash = RunnerImageExtension.hash(extension);
            boolean published = image != null && image.imageVersion() != null
                && image.imageVersion().endsWith("+ext." + hash);
            extensionJson = "{\"hash\":" + Json.string(hash)
                + ",\"bytes\":" + extension.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + ",\"published\":" + published
                + ",\"steps\":" + RunnerImageExtension.stepsByPhase(extension).entrySet().stream()
                    .map(e -> Json.string(e.getKey()) + ":" + e.getValue().stream().map(Json::string)
                        .collect(Collectors.joining(",", "[", "]")))
                    .collect(Collectors.joining(",", "{", "}")) + "}";
        }
        List<String> jdks = new ArrayList<>();
        if (image != null) {
            jdkList.ifPresent(list -> {
                try {
                    for (var entry : new ObjectMapper().readTree(list)) {
                        jdks.add("{\"id\":" + Json.string(entry.path("id").asText())
                            + ",\"release\":" + Json.string(entry.path("release").asText(null))
                            + ",\"vendor\":" + Json.string(entry.path("vendor").asText(null))
                            + ",\"default\":" + entry.path("default").asBoolean() + "}");
                    }
                } catch (java.io.IOException e) {
                    jdks.clear();
                }
            });
            if (jdks.isEmpty()) {
                image.jdkIds().forEach(id -> jdks.add("{\"id\":" + Json.string(id) + ",\"release\":null,\"vendor\":null"
                    + ",\"default\":" + id.equals(image.defaultJdk()) + "}"));
            }
        }
        return "{\"deployment\":" + Json.string(deployment) + ",\"pointer\":" + Json.string(pointer)
            + ",\"image\":" + imageJson + ",\"extension\":" + extensionJson
            + ",\"jdks\":" + jdks.stream().collect(Collectors.joining(",", "[", "]"))
            + ",\"bundledBase\":" + Json.string(bundledBase) + "}";
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

    /**
     * The JDKs a job can select with {@code baas run --jdk}: from the list the image's contract
     * recorded, with release and vendor, or from the AMI's tag alone when that list is gone.
     */
    static void printJdks(Console console, RunnerImage image, Optional<String> jdkList) {
        if (!image.selectsJdks()) {
            console.println("  JDKs:        none selectable (the image predates JDK selection; rebuild it)");
            return;
        }
        List<String[]> rows = new ArrayList<>();
        jdkList.ifPresent(json -> {
            try {
                for (var entry : new ObjectMapper().readTree(json)) {
                    rows.add(new String[]{Printable.of(entry.path("id").asText()), Printable.of(entry.path("release").asText()),
                        Printable.of(entry.path("vendor").asText()), entry.path("default").asBoolean() ? "(default)" : ""});
                }
            } catch (java.io.IOException e) {
                rows.clear();
            }
        });
        if (rows.isEmpty()) {
            image.jdkIds().forEach(id -> rows.add(new String[]{id, "", "", id.equals(image.defaultJdk()) ? "(default)" : ""}));
        }
        int[] width = new int[3];
        rows.forEach(row -> { for (int i = 0; i < 3; i++) width[i] = Math.max(width[i], row[i].length()); });
        String label = "  JDKs:        ";
        for (String[] row : rows) {
            var line = new StringBuilder(label);
            for (int i = 0; i < 4; i++) {
                if (i == 3 || width[i] > 0) {
                    line.append(i < 3 ? row[i] + " ".repeat(width[i] - row[i].length() + 2) : row[i]);
                }
            }
            console.println(line.toString().stripTrailing());
            label = " ".repeat(label.length());
        }
    }

    /**
     * What to do about a bundled base that differs from the published one, by direction. Newer:
     * build it. Older: upgrading the CLI is the fix — build-image refuses an older base, and saying
     * "build" here once advised exactly the downgrade it now refuses.
     */
    static String driftWarning(String deployment, String bundledBase, String deployedBase) {
        if (deployedBase == null || bundledBase.equals(deployedBase)) {
            return null;
        }
        if (RunnerImageParameters.compareVersions(bundledBase, deployedBase) > 0) {
            return "This CLI bundles runner-image base " + bundledBase + ", but the published image is built on "
                + deployedBase + " — run `" + Hints.command(deployment, "admin image build") + "` to publish it.";
        }
        return "This CLI bundles runner-image base " + bundledBase + ", older than the published image's "
            + deployedBase + " — upgrade the CLI (" + CliVersionCheck.installCommand(BaasVersion.sourceRepo())
            + "); build-image refuses an older base.";
    }

    /** {@code 1.3.0} from {@code 1.3.0+ext.3f9a1c2e}; null for an image with no version tag. */
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
