package pl.wsztajerowski.baas.deployment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import pl.wsztajerowski.baas.cli.CommandRefusal;
import pl.wsztajerowski.baas.cli.Invocation;
import pl.wsztajerowski.baas.cli.LoggingMixin;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.console.Hints;
import pl.wsztajerowski.baas.image.RunnerImageExtension;
import pl.wsztajerowski.baas.image.RunnerImageParameters;
import pl.wsztajerowski.baas.session.BaasConfig;
import pl.wsztajerowski.baas.session.DeployerSession;
import pl.wsztajerowski.baas.session.DeploymentNames;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Supplier;

@Command(
    name = "import",
    mixinStandardHelpOptions = true,
    description = {
        "Load a bundle written by `admin deployment export` into this deployment, which must be set up.",
        "",
        "Parts (--parts, every part the bundle holds by default):",
        Part.Help.RESULTS_LINE,
        Part.Help.RECORDINGS_LINE,
        Part.Help.EXTENSION_LINE,
        "",
        "Nothing is overwritten: an item or object whose key the deployment already holds is skipped, so a "
            + "re-run after an interrupt loads only what the first run did not. The extension is not pushed: "
            + "import prints the `admin image build --extension` command that does.",
        "",
        Part.Help.NEVER_INCLUDED,
        ""
    }
)
public class ImportCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(ImportCommand.class);

    @Mixin LoggingMixin loggingMixin;

    @Spec CommandSpec spec;

    @Parameters(index = "0", paramLabel = "<bundle>", description = "The directory `admin deployment export` wrote.")
    Path bundleDirectory;

    @Option(names = "--parts", paramLabel = "<part>[,<part>...]",
        description = "Which of the bundle's parts to load: results, recordings, extension. "
            + "Default: every part the bundle holds.")
    String parts;

    /** The deployer's AWS calls. Replaced by tests with a hand-written fake. */
    Function<BaasConfig, ExportAws> awsFor = config -> new AwsExportAws(DeployerSession.factory(config));

    /** Set by tests; otherwise built from picocli's {@code getOut()} on first use. */
    Console console;

    /** Replaced in tests; reads one answer from the terminal. */
    Supplier<String> answerReader = () -> System.console().readLine();

    @Override
    public Integer call() throws Exception {
        Set<Part> requested = parts == null ? null : Part.parse(parts);
        Bundle bundle = new Bundle(bundleDirectory);
        Bundle.Manifest manifest = bundle.readManifest().orElseThrow(() -> CommandRefusal.refused(bundleDirectory
            + " holds no " + Bundle.MANIFEST_FILE + ": the export that wrote it did not complete, or it is not a "
            + "bundle. Nothing was imported."));
        if (manifest.schemaVersion() != Bundle.SCHEMA_VERSION) {
            throw CommandRefusal.refused(bundleDirectory + " is a bundle of schema version " + manifest.schemaVersion()
                + "; this baas reads version " + Bundle.SCHEMA_VERSION + ". Nothing was imported.");
        }
        Set<Part> held = manifest.partSet();
        Set<Part> selected = requested == null ? held : requested;
        Set<Part> missing = EnumSet.noneOf(Part.class);
        missing.addAll(selected);
        missing.removeAll(held);
        if (!missing.isEmpty()) {
            throw CommandRefusal.refused(bundleDirectory + " holds no " + Part.ids(missing) + "; it holds "
                + Part.ids(held) + ". Nothing was imported.");
        }
        // Everything local is checked before any AWS call: a damaged bundle fails here, whole.
        DeploymentImport load = DeploymentImport.prepare(bundle, selected);

        BaasConfig config = Invocation.configService(spec).load();
        String target = config.requirePrefix();
        if (!console().interactive()) {
            throw CommandRefusal.refused("Import asks for the target deployment's name on a terminal, and there is "
                + "none here. Nothing was imported.");
        }

        var names = DeploymentNames.of(target);
        try (ExportAws aws = awsFor.apply(config)) {
            String account = call(target, "sts:GetCallerIdentity", aws::account);
            if (!call(target, "dynamodb:DescribeTable", () -> aws.tableExists(names.resultsTable()))
                || !call(target, "s3:ListBucket", () -> aws.bucketExists(names.bucket()))) {
                throw CommandRefusal.refused(target + " is not set up: its results table or bucket does not exist. Run `"
                    + Hints.command(target, "admin deployment setup") + "` first. Nothing was imported.");
            }

            describe(manifest, selected, target, account, config.getAws().resolveRegion());
            console().print("Type the target deployment's name to import into it [" + target + "]: ");
            String answer = answerReader.get();
            if (answer == null || !target.equals(answer.strip())) {
                logger.info("Aborted. Nothing was imported.");
                return 1;
            }

            if (selected.contains(Part.RESULTS)) {
                long[] written = load.results(aws, names.resultsTable(), target);
                console().printf("  results     %d written, %d already present%n", written[0], written[1]);
            }
            if (selected.contains(Part.RECORDINGS)) {
                long[] written = load.recordings(aws, names.bucket(), target);
                console().printf("  recordings  %d written, %d already present%n", written[0], written[1]);
            }
            if (selected.contains(Part.EXTENSION)) {
                handOffExtension(aws, bundle, manifest, target);
            }
        }
        return 0;
    }

    private void describe(Bundle.Manifest m, Set<Part> selected, String target, String account, String region) {
        console().println("Import " + bundleDirectory.toAbsolutePath().normalize());
        console().println("  from  " + m.deployment() + " (account " + m.account() + ", " + m.region()
            + "), exported " + m.exportedAt());
        console().println("  into  " + target + " (account " + account + ", " + region + ")");
        if (selected.contains(Part.RESULTS)) {
            console().printf("  results     %d items: %d measurements, %d jobs%n", m.items(), m.measurementItems(), m.jobItems());
        }
        if (selected.contains(Part.RECORDINGS)) {
            console().printf("  recordings  %d objects, %d bytes%n", m.objects(), m.bytes());
        }
        if (selected.contains(Part.EXTENSION)) {
            console().println("  extension   " + (m.extensionHash() == null ? "none in the bundle" : m.extensionHash()));
        }
        console().println("Nothing the deployment already holds is overwritten.");
    }

    /**
     * The push is a ~15-minute image build, which import never starts uninvited. The stale-copy
     * guard on {@code build-image --extension} then accepts the file into a deployment holding no
     * extension and refuses it over a different one.
     */
    private void handOffExtension(ExportAws aws, Bundle bundle, Bundle.Manifest manifest, String target) {
        if (manifest.extensionHash() == null) {
            console().println("  extension   " + manifest.deployment() + " held none; nothing to push.");
            return;
        }
        String deployed = RunnerImageExtension.parse(call(target, "cloudformation:DescribeStacks",
            () -> aws.stackParameters(target)).getOrDefault(RunnerImageParameters.EXTENSION_DATA, "")).content();
        if (manifest.extensionHash().equals(RunnerImageExtension.identity(deployed))) {
            console().println("  extension   " + manifest.extensionHash() + " is already deployed on " + target + ".");
            return;
        }
        console().println("  extension   " + manifest.extensionHash() + " is in " + bundle.extension()
            + ". Push it, which rebuilds the image, with:");
        console().println("                " + Hints.command(target, "admin image build --extension " + bundle.extension()));
    }

    private static <T> T call(String deployment, String action, Supplier<T> step) {
        try {
            return step.get();
        } catch (RuntimeException e) {
            throw DeploymentExport.translated(action, deployment, e);
        }
    }

    private Console console() {
        if (console == null) {
            console = Console.of(spec.commandLine().getOut());
        }
        return console;
    }
}
