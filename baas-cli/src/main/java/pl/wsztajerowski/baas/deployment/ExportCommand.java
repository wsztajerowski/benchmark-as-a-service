package pl.wsztajerowski.baas.deployment;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import pl.wsztajerowski.baas.cli.Invocation;
import pl.wsztajerowski.baas.cli.LoggingMixin;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.console.Hints;
import pl.wsztajerowski.baas.session.BaasConfig;
import pl.wsztajerowski.baas.session.DeployerSession;

import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Function;

@Command(
    name = "export",
    mixinStandardHelpOptions = true,
    description = {
        "Copy the deployment's data into a local bundle, which `admin deployment import` loads back.",
        "",
        "Parts (--parts, all three by default):",
        Part.Help.RESULTS_LINE,
        Part.Help.RECORDINGS_LINE,
        Part.Help.EXTENSION_LINE,
        "",
        Part.Help.NEVER_INCLUDED,
        "",
        "Export only reads. Run while a job is in flight, it can miss what that job writes afterwards; "
            + "teardown offers it only once no job is running.",
        ""
    }
)
public class ExportCommand implements Callable<Integer> {

    @Mixin LoggingMixin loggingMixin;

    @Spec CommandSpec spec;

    @Option(names = "--parts", paramLabel = "<part>[,<part>...]",
        description = "Which parts to export: results, recordings, extension. Default: all three.")
    String parts;

    @Option(names = "--without-inputs",
        description = "Leave every job's input/ JARs out of recordings.")
    boolean withoutInputs;

    @Option(names = {"-o", "--output-dir"},
        description = "A new or empty directory to write the bundle into. "
            + "Default: ./baas-export-<deployment>-<UTC instant>.")
    Path outputDir;

    /** The deployer's AWS calls. Replaced by tests with a hand-written fake. */
    Function<BaasConfig, ExportAws> awsFor = config -> new AwsExportAws(DeployerSession.factory(config));

    @Override
    public Integer call() throws Exception {
        Set<Part> selected = parts == null ? EnumSet.allOf(Part.class) : Part.parse(parts);
        BaasConfig config = Invocation.configService(spec).load();
        String deployment = config.requirePrefix();
        Instant now = Instant.now();
        Path directory = outputDir != null ? outputDir : DeploymentExport.defaultDirectory(deployment, now);

        DeploymentExport.Outcome outcome;
        try (ExportAws aws = awsFor.apply(config)) {
            outcome = new DeploymentExport(aws, deployment, config.getAws().resolveRegion(), () -> now)
                .run(directory, selected, withoutInputs);
        }
        report(Console.of(spec.commandLine().getOut()), deployment, outcome);
        return 0;
    }

    static void report(Console console, String deployment, DeploymentExport.Outcome outcome) {
        var m = outcome.manifest();
        console.println("Exported " + deployment + " to " + outcome.bundle().root().toAbsolutePath().normalize());
        var parts = m.partSet();
        if (parts.contains(Part.RESULTS)) {
            console.printf("  results     %d items: %d measurements, %d jobs%n", m.items(), m.measurementItems(), m.jobItems());
        }
        if (parts.contains(Part.RECORDINGS)) {
            console.printf("  recordings  %d objects, %d bytes%s%n", m.objects(), m.bytes(),
                m.withoutInputs() ? ", without inputs" : "");
        }
        if (parts.contains(Part.EXTENSION)) {
            console.println("  extension   " + (m.extensionHash() == null ? "none deployed" : m.extensionHash()));
        }
        console.println("Load it into a set-up deployment with: "
            + Hints.command(deployment, "admin deployment import " + outcome.bundle().root()));
    }
}
