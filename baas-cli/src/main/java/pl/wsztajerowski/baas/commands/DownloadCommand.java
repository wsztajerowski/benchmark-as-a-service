package pl.wsztajerowski.baas.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import pl.wsztajerowski.baas.LoggingMixin;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.config.ConfigService;
import pl.wsztajerowski.baas.infra.AwsClientFactory;
import pl.wsztajerowski.baas.infra.S3UploadService;
import pl.wsztajerowski.baas.results.ResultsQueryService;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

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

    @Option(names = {"-o", "--output-dir"},
        description = "Local directory to write into. Default: ./<last path segment>.")
    Path outputDir;

    private final ConfigService configService = new ConfigService();

    private static final Pattern RUN_ID = Pattern.compile("\\d{8}T\\d{9}Z-[0-9a-f]{8}");

    /**
     * A path is never a run identifier and a run identifier never contains a slash, so the two
     * argument shapes cannot be confused. The path branch is what keeps every run stored before
     * this layout retrievable — {@code baas download} follows each item's stored {@code resultPath}
     * rather than reconstructing one.
     */
    static boolean looksLikeRunId(String argument) {
        return argument != null && RUN_ID.matcher(argument).matches();
    }

    /** Split out from {@link #call()} so the guard is reachable without AWS credentials. */
    boolean tableUnresolvable(String table) {
        return table == null || table.isBlank();
    }

    @Override
    public Integer call() {
        BaasConfig config = configService.load();
        RunCommand.operatorCredentialsWarning(config).ifPresent(logger::warn);

        String bucket = config.getAws().getBucket();
        if (bucket == null || bucket.isBlank()) {
            logger.error("No bucket in config. Run: baas config sync --core-stack-name <stack>");
            return 1;
        }

        var factory = new AwsClientFactory(
            config.getAws().getRegion(), config.getAws().resolveOperatorProfile());

        String resolvedPath = resultPath;
        if (looksLikeRunId(resultPath)) {
            // Only the run-id branch needs the table; a literal path resolves without it, so this
            // is checked here rather than beside the bucket check above.
            String table = config.getAws().getResultsTable();
            if (tableUnresolvable(table)) {
                logger.error("""
                    No results table in config, so run id '{}' cannot be resolved to a path.
                      Sync it from the stack:  baas config sync --core-stack-name {}
                      Or pass the run's result path directly.
                    Nothing was written.""", resultPath, config.getAws().getCoreStackName());
                return 1;
            }
            try (var results = new ResultsQueryService(factory.dynamoDb(), table)) {
                resolvedPath = results.resultPathForRun(resultPath);
            }
            // Before anything is written, so an unknown run leaves no partial directory behind.
            if (resolvedPath == null) {
                logger.error("No run found with id '{}'. Nothing was written.", resultPath);
                return 1;
            }
            logger.debug("Run {} resolves to {}", resultPath, resolvedPath);
        }
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
