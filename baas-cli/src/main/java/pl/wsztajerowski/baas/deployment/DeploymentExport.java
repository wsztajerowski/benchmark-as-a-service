package pl.wsztajerowski.baas.deployment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.baas.aws.AwsErrors;
import pl.wsztajerowski.baas.cli.BaasVersion;
import pl.wsztajerowski.baas.cli.CommandRefusal;
import pl.wsztajerowski.baas.console.Hints;
import pl.wsztajerowski.baas.image.RunnerImageExtension;
import pl.wsztajerowski.baas.image.RunnerImageParameters;
import pl.wsztajerowski.baas.jobs.JobsDownloadSubcommand;
import pl.wsztajerowski.baas.model.JobLayout;
import pl.wsztajerowski.baas.model.ResultKeys;
import pl.wsztajerowski.baas.model.dynamodb.JobItemMapper;
import pl.wsztajerowski.baas.model.dynamodb.MeasurementItemMapper;
import pl.wsztajerowski.baas.session.DeploymentNames;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * Writes one deployment's data into a bundle, part by part, and the manifest last: a directory
 * without one is an export that did not finish, which import refuses. Used by
 * {@code baas admin deployment export} and by teardown's offer to export first.
 */
final class DeploymentExport {

    private static final Logger logger = LoggerFactory.getLogger(DeploymentExport.class);

    /** Downloads in flight at once. Small: the bottleneck is the laptop's link, not S3. */
    static final int POOL_SIZE = 8;

    private static final DateTimeFormatter BASIC =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /** What was written, for the command's report. */
    record Outcome(Bundle bundle, Bundle.Manifest manifest) {
    }

    private final ExportAws aws;
    private final String deployment;
    private final String region;
    private final Supplier<Instant> clock;

    DeploymentExport(ExportAws aws, String deployment, String region, Supplier<Instant> clock) {
        this.aws = aws;
        this.deployment = deployment;
        this.region = region;
        this.clock = clock;
    }

    static Path defaultDirectory(String deployment, Instant at) {
        return Path.of("baas-export-" + deployment + "-" + BASIC.format(at));
    }

    Outcome run(Path directory, Set<Part> parts, boolean withoutInputs) throws IOException {
        Bundle bundle = new Bundle(directory);
        if (!bundle.isEmptyOrAbsent()) {
            throw CommandRefusal.refused(directory + " exists and is not empty. Name a new or empty directory with "
                + "--output-dir. Nothing was exported.");
        }
        Instant exportedAt = clock.get();
        String account = call("sts:GetCallerIdentity", aws::account);
        Files.createDirectories(directory);
        var names = DeploymentNames.of(deployment);

        long[] items = new long[3];
        if (parts.contains(Part.RESULTS)) {
            items = exportResults(bundle, names.resultsTable());
        }
        String extensionHash = null;
        if (parts.contains(Part.EXTENSION)) {
            extensionHash = exportExtension(bundle).orElse(null);
        }
        long[] objects = new long[2];
        if (parts.contains(Part.RECORDINGS)) {
            objects = exportRecordings(bundle, names.bucket(), withoutInputs);
        }

        var manifest = new Bundle.Manifest(Bundle.SCHEMA_VERSION, deployment, account, region,
            exportedAt.toString(), BaasVersion.current(),
            Arrays.stream(Part.values()).filter(parts::contains).map(p -> p.id).toList(),
            withoutInputs, items[0], items[1], items[2], objects[0], objects[1], extensionHash);
        bundle.writeManifest(manifest);
        return new Outcome(bundle, manifest);
    }

    /** Streams the scan straight into the file; returns {all, measurement, job} item counts. */
    private long[] exportResults(Bundle bundle, String table) throws IOException {
        long[] counts = new long[3];
        logger.info("Exporting the results table {}", table);
        try (BufferedWriter out = Files.newBufferedWriter(bundle.results(), StandardCharsets.UTF_8)) {
            call("dynamodb:Scan", () -> {
                aws.scan(table, item -> {
                    try {
                        out.write(ItemJson.encode(item));
                        out.write('\n');
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    counts[0]++;
                    if (JobItemMapper.isJobItem(item)) {
                        counts[2]++;
                    } else if (item.containsKey(MeasurementItemMapper.PK)
                        && item.get(MeasurementItemMapper.PK).s() != null
                        && item.get(MeasurementItemMapper.PK).s().startsWith(ResultKeys.PK_PREFIX)) {
                        counts[1]++;
                    }
                });
                return null;
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        try (var lines = Files.lines(bundle.results(), StandardCharsets.UTF_8)) {
            long written = lines.count();
            if (written != counts[0]) {
                throw CommandRefusal.refused(bundle.results() + " holds " + written + " lines for " + counts[0]
                    + " items scanned. No manifest was written.");
            }
        }
        return counts;
    }

    /** The extension with its base marker, so a later push is judged by the stale-copy guard. */
    private Optional<String> exportExtension(Bundle bundle) throws IOException {
        var parameters = call("cloudformation:DescribeStacks", () -> aws.stackParameters(deployment));
        String content = RunnerImageExtension.parse(
            parameters.getOrDefault(RunnerImageParameters.EXTENSION_DATA, "")).content();
        if (content.isEmpty()) {
            logger.info("{} holds no runner-image extension.", deployment);
            return Optional.empty();
        }
        Files.writeString(bundle.extension(), RunnerImageExtension.withMarker(content));
        return Optional.of(RunnerImageExtension.hash(content));
    }

    /** Mirrors {@code jobs/} key by key; returns {objects, bytes}. */
    private long[] exportRecordings(Bundle bundle, String bucket, boolean withoutInputs) {
        String prefix = JobLayout.JOBS_PREFIX + "/";
        List<ExportAws.StoredObject> objects = call("s3:ListBucket", () -> aws.list(bucket, prefix)).stream()
            .filter(o -> !o.key().endsWith("/"))
            .filter(o -> !(withoutInputs && isInput(o.key())))
            .toList();

        // Every destination is checked before anything is written: a key is an arbitrary string, and
        // whatever can write the bucket must not choose where this command writes on the laptop.
        List<Path> destinations = new ArrayList<>();
        List<String> escaping = new ArrayList<>();
        for (var object : objects) {
            JobsDownloadSubcommand.destinationFor(bundle.recordings(), "", object.key())
                .ifPresentOrElse(destinations::add, () -> escaping.add(object.key()));
        }
        if (!escaping.isEmpty()) {
            throw CommandRefusal.refused(escaping.size() + " key(s) under " + prefix + " would land outside "
                + bundle.recordings() + " and were not written: " + String.join(", ", escaping.subList(0,
                Math.min(5, escaping.size()))) + ". No manifest was written.");
        }

        long bytes = objects.stream().mapToLong(ExportAws.StoredObject::size).sum();
        logger.info("Downloading {} object(s), {} bytes, from s3://{}/{}", objects.size(), bytes, bucket, prefix);
        ExecutorService pool = Executors.newFixedThreadPool(POOL_SIZE);
        try {
            List<Future<?>> downloads = new ArrayList<>();
            for (int i = 0; i < objects.size(); i++) {
                String key = objects.get(i).key();
                Path destination = destinations.get(i);
                downloads.add(pool.submit(() -> aws.download(bucket, key, destination)));
            }
            awaitAll(downloads, "s3:GetObject");
        } finally {
            pool.shutdownNow();
        }

        for (int i = 0; i < objects.size(); i++) {
            long local = size(destinations.get(i));
            if (local != objects.get(i).size()) {
                throw CommandRefusal.refused("s3://" + bucket + "/" + objects.get(i).key() + " is " + objects.get(i).size()
                    + " bytes in the bucket but " + local + " on disk. No manifest was written; export again.");
            }
        }
        return new long[]{objects.size(), bytes};
    }

    /** {@code jobs/<project>/<jobId>/input/…}: what `baas run` uploaded, left out by --without-inputs. */
    static boolean isInput(String key) {
        String[] segments = key.split("/", 5);
        return segments.length == 5 && JobLayout.INPUT_SEGMENT.equals(segments[3]);
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    /** Waits for every task; the first failure fails the export, translated like a direct call. */
    void awaitAll(List<? extends Future<?>> tasks, String action) {
        RuntimeException first = null;
        for (var task : tasks) {
            try {
                task.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted", e);
            } catch (ExecutionException e) {
                if (first == null) {
                    first = e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause());
                }
            }
        }
        if (first != null) {
            throw translated(action, deployment, first);
        }
    }

    /** One AWS step, with a refused permission turned into the hint that fixes it. */
    <T> T call(String action, Supplier<T> step) {
        try {
            return step.get();
        } catch (RuntimeException e) {
            throw translated(action, deployment, e);
        }
    }

    /**
     * Every deployment set up before export existed carries a deployer policy without its actions;
     * the fix is the policy setup hands over, so that is what the refusal names.
     */
    static RuntimeException translated(String action, String deployment, RuntimeException failure) {
        if (!AwsErrors.isAccessDenied(failure)) {
            return failure;
        }
        return CommandRefusal.refused("AWS refused " + action + " for " + deployment + " (" + failure.getMessage()
            + ").\n  The attached deployer policy may predate export and import: run `"
            + Hints.command(deployment, "admin deployment setup") + "`, which hands over the current one.");
    }
}
