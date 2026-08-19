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

    @Parameters(index = "0", paramLabel = "<resultPath>",
        description = "The run's result path, as reported by `baas results` (e.g. main/jmh/20260819_090000).")
    String resultPath;

    @Option(names = {"-o", "--output-dir"},
        description = "Local directory to write into. Default: ./<last path segment>.")
    Path outputDir;

    private final ConfigService configService = new ConfigService();

    @Override
    public Integer call() {
        BaasConfig config = configService.load();
        RunCommand.operatorCredentialsWarning(config).ifPresent(logger::warn);

        String bucket = config.getAws().getBucket();
        if (bucket == null || bucket.isBlank()) {
            logger.error("No bucket in config. Run: baas config sync --core-stack-name <stack>");
            return 1;
        }

        String prefix = resultPath.endsWith("/") ? resultPath : resultPath + "/";

        var factory = new AwsClientFactory(
            config.getAws().getRegion(), config.getAws().resolveOperatorProfile());

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

            Path destinationRoot = outputDir != null ? outputDir : Path.of(lastSegment(resultPath));
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

    /** A result path is {@code <branch>/<type>/<timestamp>}; the timestamp alone names the run. */
    private static String lastSegment(String path) {
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = trimmed.lastIndexOf('/');
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
    }
}
