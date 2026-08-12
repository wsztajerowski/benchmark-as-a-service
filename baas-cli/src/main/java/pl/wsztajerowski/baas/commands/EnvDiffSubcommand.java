package pl.wsztajerowski.baas.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;
import pl.wsztajerowski.baas.LoggingMixin;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.config.ConfigService;
import pl.wsztajerowski.baas.infra.AwsClientFactory;
import pl.wsztajerowski.baas.infra.S3UploadService;
import pl.wsztajerowski.baas.results.EnvironmentManifest;

import java.util.Optional;
import java.util.concurrent.Callable;

@Command(
    name = "diff",
    mixinStandardHelpOptions = true,
    description = "Report the environment fields that differ between two runs.",
    footer = {
        "",
        "Result paths are <branch>/<type>/<timestamp>, as printed by baas run:",
        "  baas env diff main/jmh/20260724_120000 main/jmh/20260811_093000"
    }
)
public class EnvDiffSubcommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(EnvDiffSubcommand.class);

    @Mixin LoggingMixin loggingMixin;

    @Parameters(index = "0", paramLabel = "<resultPathA>", description = "First run's result path.")
    String resultPathA;

    @Parameters(index = "1", paramLabel = "<resultPathB>", description = "Second run's result path.")
    String resultPathB;

    private final ConfigService configService = new ConfigService();

    @Override
    public Integer call() {
        BaasConfig config = configService.load();
        RunCommand.operatorCredentialsWarning(config).ifPresent(logger::warn);

        // Read-only, day-to-day: operator credentials, like `run` and `results`.
        var factory = new AwsClientFactory(
            config.getAws().getRegion(), config.getAws().resolveOperatorProfile());
        String bucket = config.getAws().getBucket();

        EnvironmentManifest a;
        EnvironmentManifest b;
        try (var s3 = factory.s3()) {
            var storage = new S3UploadService(s3);
            var fetchedA = fetch(storage, bucket, resultPathA);
            var fetchedB = fetch(storage, bucket, resultPathB);
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

        // Command payload, so stdout rather than the logger — a timestamp prefix on every line
        // breaks redirecting this to a file, the same reasoning as ResultsCommand#printJson.
        if (differences.isEmpty()) {
            System.out.println("No differences. Both runs measured on the same environment.");
            return 0;
        }

        System.out.printf("%-24s %-34s %-34s%n", "FIELD", shorten(resultPathA), shorten(resultPathB));
        System.out.println("-".repeat(94));
        differences.forEach((field, difference) -> System.out.printf("%-24s %-34s %-34s%n",
            field, orAbsent(difference.left()), orAbsent(difference.right())));
        return 0;
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
