package pl.wsztajerowski.baas.deployment;

import pl.wsztajerowski.baas.cli.CommandRefusal;
import pl.wsztajerowski.baas.model.JobLayout;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * A bundle's selected parts, read and checked whole before anything is written, then loaded with
 * conditional writes only. The counterpart of {@link DeploymentExport}; {@code ImportCommand} asks,
 * this writes.
 */
final class DeploymentImport {

    private final Bundle bundle;
    private final List<Map<String, AttributeValue>> items;
    private final List<Path> files;

    private DeploymentImport(Bundle bundle, List<Map<String, AttributeValue>> items, List<Path> files) {
        this.bundle = bundle;
        this.items = items;
        this.files = files;
    }

    /** Decodes every results line and checks every recording's key; makes no AWS call. */
    static DeploymentImport prepare(Bundle bundle, Set<Part> selected) throws IOException {
        return new DeploymentImport(bundle,
            selected.contains(Part.RESULTS) ? items(bundle) : List.of(),
            selected.contains(Part.RECORDINGS) ? recordingFiles(bundle) : List.of());
    }

    /** Every line decoded before anything is written, so a damaged file fails the import up front. */
    private static List<Map<String, AttributeValue>> items(Bundle bundle) throws IOException {
        if (!Files.isRegularFile(bundle.results())) {
            throw CommandRefusal.refused(bundle.results() + " is missing. Nothing was imported.");
        }
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        int number = 0;
        for (String line : Files.readAllLines(bundle.results(), StandardCharsets.UTF_8)) {
            number++;
            if (line.isBlank()) {
                continue;
            }
            try {
                items.add(ItemJson.decode(line));
            } catch (IllegalArgumentException e) {
                throw CommandRefusal.refused(bundle.results() + " line " + number + ": " + e.getMessage()
                    + ". Nothing was imported.");
            }
        }
        return items;
    }

    /**
     * Every file under {@code recordings/}, each of whose relative paths is the key it is uploaded at.
     * Only keys under {@code jobs/} are accepted: import never writes {@code releases/}, which is
     * seeded once and never overwritten, or any prefix a bundle cannot have come from.
     */
    static List<Path> recordingFiles(Bundle bundle) throws IOException {
        if (!Files.isDirectory(bundle.recordings())) {
            return List.of();
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(bundle.recordings())) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        List<String> outside = files.stream().map(f -> keyOf(bundle, f))
            .filter(key -> !key.startsWith(JobLayout.JOBS_PREFIX + "/")).toList();
        if (!outside.isEmpty()) {
            throw CommandRefusal.refused(outside.size() + " file(s) under " + bundle.recordings() + " are not under "
                + JobLayout.JOBS_PREFIX + "/, which is all a bundle holds: "
                + String.join(", ", outside.subList(0, Math.min(5, outside.size()))) + ". Nothing was imported.");
        }
        return files;
    }

    static String keyOf(Bundle bundle, Path file) {
        Path relative = bundle.recordings().relativize(file);
        List<String> segments = new ArrayList<>();
        relative.forEach(segment -> segments.add(segment.toString()));
        return String.join("/", segments);
    }

    /** One conditional write per item; returns {written, skipped}. */
    long[] results(ExportAws aws, String table, String target) {
        long written = 0;
        for (var item : items) {
            boolean wrote;
            try {
                wrote = aws.putItemIfAbsent(table, item);
            } catch (RuntimeException e) {
                throw DeploymentExport.translated("dynamodb:PutItem", target, e);
            }
            if (wrote) {
                written++;
            }
        }
        return new long[]{written, items.size() - written};
    }

    long[] recordings(ExportAws aws, String bucket, String target) {
        AtomicLong written = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(DeploymentExport.POOL_SIZE);
        try {
            List<Future<?>> uploads = new ArrayList<>();
            for (Path file : this.files) {
                String key = keyOf(bundle, file);
                uploads.add(pool.submit(() -> {
                    if (aws.putObjectIfAbsent(bucket, key, file)) {
                        written.incrementAndGet();
                    }
                }));
            }
            DeploymentExport.awaitAll(uploads, "s3:PutObject", target);
        } finally {
            pool.shutdownNow();
        }
        return new long[]{written.get(), files.size() - written.get()};
    }

}
