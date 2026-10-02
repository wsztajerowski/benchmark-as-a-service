package pl.wsztajerowski.baas.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import pl.wsztajerowski.baas.BaasApp;
import pl.wsztajerowski.baas.LoggingMixin;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.config.ConfigService;
import pl.wsztajerowski.baas.infra.AwsClientFactory;
import pl.wsztajerowski.baas.infra.S3UploadService;
import pl.wsztajerowski.baas.results.ResultsQueryService;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Fetches everything a stored measurement deliberately leaves out.
 *
 * <p>The item is thin — {@code rawData} and {@code scorePercentiles} are dropped to stay well under
 * DynamoDB's 400 KB limit — so the CLI has to be able to retrieve the full fidelity that stayed in
 * S3. Without this command, the thin item would be a promise the CLI could not keep.
 */
@Command(
    name = "download",
    mixinStandardHelpOptions = true,
    description = "Download every S3 artifact for a run: result JSON, environment.json, process output, logs and profiling artifacts."
)
public class DownloadCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(DownloadCommand.class);

    @Mixin LoggingMixin loggingMixin;

    @Parameters(index = "0", paramLabel = "<runId|resultPath>",
        description = "The run identifier `baas run` printed and `baas results` shows "
            + "(e.g. 20260820T174432812Z-a3f9c21b), or a literal S3 result path "
            + "(e.g. main/jmh/20260819_090000) for a run stored before the unified layout.")
    String resultPath;

    // No --results-table or --bucket: another installation is reached by naming its configuration
    // with the inherited --config-path, which addresses the whole installation at once.

    @Option(names = {"-o", "--output-dir"},
        description = "Local directory to write into. Default: ./<last path segment>.")
    Path outputDir;

    /** Split out from {@link #call()} so the guard is reachable without AWS credentials. */
    boolean tableUnresolvable(String table) {
        return table == null || table.isBlank();
    }

    @Spec CommandSpec spec;

    private ConfigService configService() {
        return BaasApp.configService(spec);
    }

    @Override
    public Integer call() {
        BaasConfig config = configService().load();
        RunCommand.operatorCredentialsWarning(config).ifPresent(logger::warn);

        String bucket;
        try {
            bucket = config.bucket();
        } catch (IllegalStateException noInstallation) {
            logger.error("{}", noInstallation.getMessage());
            return 1;
        }

        var factory = new AwsClientFactory(
            config.getAws().resolveRegion(), config.getAws().resolveOperatorProfile());

        if (RunReference.looksLikeRunId(resultPath)) {
            // Only the run-id branch needs the table; a literal path resolves without it, so this
            // is checked here rather than beside the bucket check above.
            try {
                config.resultsTable();
            } catch (IllegalStateException noInstallation) {
                logger.error("""
                    No installation is configured, so run id '{}' cannot be resolved to a path.
                      Adopt one:  baas config sync --name baas-<accountId>
                      Or pass the run's result path directly, or name another installation's
                      configuration with --config-path.
                    Nothing was written.""", resultPath);
                return 1;
            }
        }
        String resolvedPath;
        try (var results = new ResultsQueryService(factory.dynamoDb(), config.resultsTable())) {
            resolvedPath = RunReference.resolve(resultPath, results::resultPathForRun);
        }
        // Before anything is written, so an unknown run leaves no partial directory behind.
        if (resolvedPath == null) {
            logger.error("{} Nothing was written.", RunReference.noSuchRun(resultPath));
            return 1;
        }
        logger.debug("{} resolves to {}", resultPath, resolvedPath);
        String prefix = resolvedPath.endsWith("/") ? resolvedPath : resolvedPath + "/";

        try (var s3 = factory.s3()) {
            var storage = new S3UploadService(s3);
            List<String> keys = storage.listKeys(bucket, prefix);

            // Listed before anything is written: the spec requires an unknown run to leave no
            // partial directory behind, and S3 has no directory whose absence we could check.
            if (keys.isEmpty()) {
                logger.error("No artifacts found for run '{}' in bucket {}. Nothing was written.",
                    resultPath, bucket);
                return 1;
            }

            Path destinationRoot = outputDir != null ? outputDir : Path.of(lastSegment(resolvedPath));
            for (String key : keys) {
                Path destination = destinationRoot.resolve(key.substring(prefix.length()));
                logger.debug("Downloading {} -> {}", key, destination);
                storage.download(bucket, key, destination);
            }
            logger.info("Downloaded {} artifact(s) for run '{}' to {}",
                keys.size(), resultPath, destinationRoot.toAbsolutePath().normalize());
        }
        return 0;
    }

    /** The last segment of a run prefix is the run identifier, which is what names the directory. */
    private static String lastSegment(String path) {
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = trimmed.lastIndexOf('/');
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
    }
}
