package pl.wsztajerowski.baas.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Parameters;
import pl.wsztajerowski.baas.BaasApp;
import pl.wsztajerowski.baas.LoggingMixin;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.config.ConfigService;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.console.Table;
import pl.wsztajerowski.baas.console.Table.Cell;
import pl.wsztajerowski.baas.console.Table.Column;
import pl.wsztajerowski.baas.infra.AwsClientFactory;
import pl.wsztajerowski.baas.infra.S3UploadService;
import pl.wsztajerowski.baas.results.EnvironmentManifest;
import pl.wsztajerowski.baas.results.ResultsQueryService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

@Command(
    name = "diff",
    mixinStandardHelpOptions = true,
    description = "Report the environment fields that differ between two runs.",
    footer = {
        "",
        "Name each run by the id baas run and baas results show, or by its",
        "result path (runs/<project>/<runId>):",
        "  baas env diff 20260724T120000000Z-a3f9c21b 20260811T093000000Z-b7e4d0f2",
        "",
        "A run that failed before storing a measurement has no index entry:",
        "name it by its result path.",
        "",
        "A run recorded before the unified layout keeps its original path",
        "(<branch>/<type>/<timestamp>); both shapes still resolve."
    }
)
public class EnvDiffSubcommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(EnvDiffSubcommand.class);

    @Mixin LoggingMixin loggingMixin;

    @Spec CommandSpec spec;

    /** Set by tests; otherwise built from picocli's {@code getOut()} on first use. */
    Console console;

    @Parameters(index = "0", paramLabel = "<runA>",
        description = "First run: its run id, or its result path.")
    String resultPathA;

    @Parameters(index = "1", paramLabel = "<runB>",
        description = "Second run: its run id, or its result path.")
    String resultPathB;

    /**
     * Resolves a run id to its stored result path. Overridden by tests, which have no table. Only
     * called for an argument shaped like a run id, so two literal paths never touch DynamoDB.
     */
    ResultsQueryService runLookup(BaasConfig config, AwsClientFactory factory) {
        return new ResultsQueryService(factory.dynamoDb(), config.resultsTable());
    }

    private ConfigService configService() {
        return BaasApp.configService(spec);
    }

    @Override
    public Integer call() {
        BaasConfig config = configService().load();
        RunCommand.operatorCredentialsWarning(config).ifPresent(logger::warn);

        // Read-only, day-to-day: operator credentials, like `run` and `results`.
        var factory = new AwsClientFactory(
            config.getAws().resolveRegion(), config.getAws().resolveOperatorProfile());
        String bucket = config.bucket();

        String pathA;
        String pathB;
        try (var lookup = runLookup(config, factory)) {
            pathA = RunReference.resolve(resultPathA, lookup::resultPathForRun);
            pathB = RunReference.resolve(resultPathB, lookup::resultPathForRun);
        }
        for (var unresolved : new String[][]{{resultPathA, pathA}, {resultPathB, pathB}}) {
            if (unresolved[1] == null) {
                logger.error("{}", RunReference.noSuchRun(unresolved[0]));
                return 1;
            }
        }

        EnvironmentManifest a;
        EnvironmentManifest b;
        try (var s3 = factory.s3()) {
            var storage = new S3UploadService(s3);
            var fetchedA = fetch(storage, bucket, pathA);
            var fetchedB = fetch(storage, bucket, pathB);
            if (fetchedA.isEmpty() || fetchedB.isEmpty()) {
                return 1;
            }
            a = fetchedA.get();
            b = fetchedB.get();
        }

        a.schemaVersion().ifPresent(versionA -> b.schemaVersion().ifPresent(versionB -> {
            if (!versionA.equals(versionB)) {
                // Not a failure: the field-by-field diff still holds. But a field that appears or
                // disappears across a schema change is a change in the record, not the environment.
                logger.warn("Manifests use different schema versions ({} vs {}) — added or removed "
                    + "fields below may reflect that rather than an environment change", versionA, versionB);
            }
        }));

        var differences = EnvironmentManifest.diff(a, b);

        printDiff(console(), differences);
        return 0;
    }

    /**
     * Command payload, so the {@link Console} rather than the logger — a timestamp prefix on every
     * line breaks redirecting this to a file, the same reasoning as ResultsCommand#printJson. The
     * two runs' values are coloured apart when the terminal allows it.
     */
    void printDiff(Console out, Map<String, EnvironmentManifest.Difference> differences) {
        if (differences.isEmpty()) {
            out.println("No differences. Both runs measured on the same environment.");
            return;
        }
        var table = new Table(out, 94, List.of(
            Column.left("FIELD", 24),
            Column.left(shorten(resultPathA), 34),
            Column.left(shorten(resultPathB), 34)));
        table.printHeader();
        differences.forEach((field, difference) -> table.printRow(List.of(
            Cell.plain(field),
            new Cell(orAbsent(difference.left()), out::red),
            new Cell(orAbsent(difference.right()), out::green))));
    }

    private Console console() {
        if (console == null) {
            console = Console.of(spec.commandLine().getOut());
        }
        return console;
    }

    private Optional<EnvironmentManifest> fetch(S3UploadService storage, String bucket, String resultPath) {
        String key = resultPath + "/environment.json";
        var body = storage.getObjectIfExists(bucket, key);
        if (body.isEmpty()) {
            logger.error("""
                No environment.json at s3://{}/{}
                  Runs from before the prebaked-image change carry no environment manifest, and
                  a run that never started writes none.""", bucket, key);
            return Optional.empty();
        }
        return Optional.of(EnvironmentManifest.parse(resultPath, body.get()));
    }

    /** A field present in only one manifest reads as blank otherwise, which looks like a bug. */
    private static String orAbsent(String value) {
        return value.isEmpty() ? "(absent)" : value;
    }

    private static String shorten(String resultPath) {
        return resultPath.length() <= 34 ? resultPath : "…" + resultPath.substring(resultPath.length() - 33);
    }
}
