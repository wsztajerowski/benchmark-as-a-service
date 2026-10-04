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
import java.util.Optional;
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

        Path destinationRoot;
        try {
            destinationRoot = outputDir != null ? outputDir : defaultRoot(resolvedPath);
        } catch (IllegalArgumentException e) {
            logger.error("{} Nothing was written.", e.getMessage());
            return 1;
        }

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

            int refused = 0;
            for (String key : keys) {
                var destination = destinationFor(destinationRoot, prefix, key);
                if (destination.isEmpty()) {
                    logger.warn("Skipped {}: it would be written outside {}.", key,
                        destinationRoot.toAbsolutePath().normalize());
                    refused++;
                    continue;
                }
                logger.debug("Downloading {} -> {}", key, destination.get());
                storage.download(bucket, key, destination.get());
            }
            logger.info("Downloaded {} artifact(s) for run '{}' to {}",
                keys.size() - refused, resultPath, destinationRoot.toAbsolutePath().normalize());
            // A key that climbs out of the run's directory was never written by BaaS, so the run's
            // prefix has been tampered with — worth a non-zero exit, not just a log line.
            if (refused > 0) {
                logger.error("{} key(s) under {} point outside the output directory and were not "
                    + "written. Inspect them with: aws s3 ls --recursive s3://{}/{}",
                    refused, prefix, bucket, prefix);
                return 1;
            }
        }
        return 0;
    }

    /**
     * Where a key lands under {@code root}, or empty when it would land anywhere else. An S3 key is
     * an arbitrary string, and anything holding {@code s3:PutObject} on the bucket — the runner role,
     * so any code in a benchmark JAR — can write one under a run's prefix; {@code ../} or a leading
     * {@code /} in it must not choose where this command writes on the operator's machine.
     */
    static Optional<Path> destinationFor(Path root, String prefix, String key) {
        Path base = root.toAbsolutePath().normalize();
        Path target = base.resolve(key.substring(prefix.length())).normalize();
        return target.startsWith(base) && !target.equals(base) ? Optional.of(target) : Optional.empty();
    }

    /**
     * The run prefix's last segment — the run identifier — names the directory. A literal path
     * ending in {@code .} or {@code ..} names none, and would otherwise make the working directory
     * or its parent the root.
     */
    static Path defaultRoot(String resolvedPath) {
        String trimmed = resolvedPath.endsWith("/") ? resolvedPath.substring(0, resolvedPath.length() - 1) : resolvedPath;
        String last = trimmed.substring(trimmed.lastIndexOf('/') + 1);
        if (last.isEmpty() || last.equals(".") || last.equals("..")) {
            throw new IllegalArgumentException(
                "'" + resolvedPath + "' does not end in a run directory; name one with --output-dir.");
        }
        return Path.of(last);
    }
}
