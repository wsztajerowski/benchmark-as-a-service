package pl.wsztajerowski.baas.jobs;

import pl.wsztajerowski.baas.cli.CommandContext;
import pl.wsztajerowski.baas.cli.JobReference;
import pl.wsztajerowski.baas.cli.CommandRefusal;
import pl.wsztajerowski.baas.console.Json;
import pl.wsztajerowski.baas.model.JobLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import pl.wsztajerowski.baas.cli.LoggingMixin;
import pl.wsztajerowski.baas.session.BaasConfig;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.console.Hints;
import pl.wsztajerowski.baas.console.Printable;
import pl.wsztajerowski.baas.model.TagKeys;
import pl.wsztajerowski.baas.aws.S3UploadService;
import pl.wsztajerowski.baas.aws.S3UploadService.StoredObject;
import pl.wsztajerowski.baas.model.JobItem;

import java.io.UncheckedIOException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.Callable;

/**
 * One job's execution: its job item, the environment it measured on, and what it left in S3. No
 * measurements — those are {@code results query --job-id}, the other domain.
 */
@Command(
    name = "show",
    mixinStandardHelpOptions = true,
    description = "Show one job: its status and instance, the environment it ran on, and its artifacts."
)
public class JobsShowSubcommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(JobsShowSubcommand.class);

    private static final DateTimeFormatter CREATED =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    @Mixin LoggingMixin loggingMixin;

    @Spec CommandSpec spec;

    @Mixin CommandContext context = new CommandContext();

    @Parameters(index = "0", paramLabel = "<jobId>",
        description = "The job identifier `baas run` printed and `baas jobs list` shows.")
    String jobId;

    @Option(names = "--format", defaultValue = "table", description = "Output format: table (default), json.")
    String format;

    /** Where a job's parts are read from. Replaced by tests, which have no AWS. */
    interface Sources extends AutoCloseable {
        Optional<JobItem> job(String jobId);

        Optional<Ec2ProvisioningService.LiveInstance> liveInstance(String jobId);

        Optional<String> manifest(String resultPath);

        List<StoredObject> objects(String resultPath);

        @Override
        void close();
    }

    Sources sources(BaasConfig config) {
        var factory = context.operator().factory();
        var dynamoDb = factory.dynamoDb();
        var ec2 = factory.ec2();
        var s3 = factory.s3();
        var recorder = new DynamoDbJobRecorder(dynamoDb, config.resultsTable());
        var instances = new Ec2ProvisioningService(ec2, config.stackName());
        var storage = new S3UploadService(s3);
        String bucket = config.bucket();
        return new Sources() {
            public Optional<JobItem> job(String id) { return recorder.find(id); }
            public Optional<Ec2ProvisioningService.LiveInstance> liveInstance(String id) { return instances.findLiveInstance(id); }
            public Optional<String> manifest(String path) { return storage.getObjectIfExists(bucket, JobLayout.artifactKey(path, JobLayout.ENVIRONMENT_NAME)); }
            public List<StoredObject> objects(String path) { return storage.listObjects(bucket, path + "/"); }
            public void close() {
                dynamoDb.close();
                ec2.close();
                s3.close();
            }
        };
    }

    @Override
    public Integer call() {
        CommandRefusal.requireFormat(this.format, List.of("table", "json"));
        String format = this.format.toLowerCase(Locale.ROOT);
        String notAJobId = JobReference.notAJobId(jobId);
        if (notAJobId != null) {
            throw CommandRefusal.usage(notAJobId);
        }
        BaasConfig config = context.operator().config();

        try (var sources = sources(config)) {
            Optional<JobItem> found = sources.job(jobId);
            if (found.isEmpty()) {
                // Before any S3 read: an unknown id names no prefix to read.
                throw CommandRefusal.refused(JobReference.noSuchJob(jobId));
            }
            JobItem job = found.get();
            Map<String, String> live = new LinkedHashMap<>();
            Optional<Instant> deadline = Optional.empty();
            if (!job.isTerminal()) {
                Optional<Ec2ProvisioningService.LiveInstance> instance = sources.liveInstance(jobId);
                instance.ifPresent(i -> live.put(jobId, i.instanceId()));
                deadline = instance.flatMap(i -> WatchdogDeadline.of(i.launchTime(), job.watchdogSeconds()));
            }
            Instant now = Instant.now();
            JobListing.Row row = JobListing.resolve(job, live, now);
            String path = trimmed(job.resultPath());
            Optional<String> manifestJson = sources.manifest(path);
            Optional<EnvironmentManifest> manifest = Optional.empty();
            if (manifestJson.isPresent()) {
                try {
                    manifest = Optional.of(EnvironmentManifest.parse(jobId, manifestJson.get()));
                } catch (UncheckedIOException e) {
                    // Stated, not fatal: the job item and its artifacts are still worth showing.
                    logger.warn("{}", e.getCause().getMessage());
                }
            }
            boolean unreadable = manifestJson.isPresent() && manifest.isEmpty();
            List<StoredObject> artifacts = relative(sources.objects(path), path);

            if (format.equals("json")) {
                printJson(row, manifest.map(EnvironmentManifest::toJson).orElse("null"),
                    artifacts.stream().map(StoredObject::key).toList());
            } else {
                printView(context.console(), row, deadline.map(d -> WatchdogDeadline.describe(d, now)), manifest,
                    unreadable, artifacts);
            }
        }
        Hints.show(context.console(), spec.commandLine().getErr(), "measurements", Hints.command(config.getPrefix(), "query --job-id " + jobId),
            "files", Hints.command(config.getPrefix(), "jobs download " + jobId));
        return 0;
    }

    void printView(Console out, JobListing.Row row, Optional<String> terminates,
                   Optional<EnvironmentManifest> manifest, boolean unreadable, List<StoredObject> artifacts) {
        JobItem job = row.job();
        // The instance writes the status and its id (X3), so both are filtered like any runner text.
        String status = Printable.of(row.status().equals(job.status()) ? row.status()
            : row.status() + " (stored: " + job.status() + ")");
        boolean hidden = "true".equals(job.tags().get(TagKeys.HIDDEN));
        out.println("Job " + job.jobId() + "   " + status + (hidden ? " · hidden" : ""));
        line(out, "Project", job.project());
        line(out, "Type", job.tags().get(TagKeys.TYPE));
        line(out, "Created", CREATED.format(job.createdAt()) + " UTC");
        String instance = row.liveInstanceId() != null ? row.liveInstanceId() : job.instanceId();
        line(out, "Instance", (instance == null ? "—" : Printable.of(instance)) + " · " + orDash(job.instanceType()));
        terminates.ifPresent(when -> line(out, "Terminates", when + ", or earlier when the job finishes"));
        line(out, "Result path", trimmed(job.resultPath()) + "/");
        if (job.errorCode() != null) {
            line(out, "Error", Printable.of(job.errorCode()));
        }
        lines(out, "Tags", tags(job.tags()));

        out.println("");
        if (manifest.isPresent()) {
            out.println("Environment (schema " + manifest.get().schemaVersion().map(Printable::of).orElse("?") + ")");
            EnvironmentView.of(manifest.get()).forEach((group, items) -> line(out, group, String.join(" · ", items)));
        } else if (unreadable) {
            out.println("Environment   unreadable — environment.json is not one JSON object");
        } else {
            out.println("Environment   none — the instance never booted, or wrote no manifest");
        }

        out.println("");
        if (artifacts.isEmpty()) {
            out.println("Artifacts   none");
        } else {
            out.println("Artifacts (" + artifacts.size() + ")");
            var groups = ArtifactView.of(artifacts);
            int width = groups.values().stream().flatMap(List::stream)
                .mapToInt(entry -> Printable.of(entry.name()).length()).max().orElse(0);
            groups.forEach((group, entries) -> lines(out, group, entries.stream()
                .map(entry -> String.format("%-" + width + "s  %10s", Printable.of(entry.name()),
                    ArtifactView.size(entry.size())))
                .toList()));
        }
    }

    private void printJson(JobListing.Row row, String environmentJson, List<String> artifacts) {
        var out = context.console();
        String artifactsJson = artifacts.stream().map(Json::string)
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        out.println("{\"job\":" + row.json() + ",\"environment\":" + environmentJson.strip()
            + ",\"artifacts\":" + artifactsJson + "}");
    }

    private static List<StoredObject> relative(List<StoredObject> objects, String path) {
        String prefix = path + "/";
        return objects.stream()
            .map(object -> object.key().startsWith(prefix)
                ? new StoredObject(object.key().substring(prefix.length()), object.size()) : object)
            .filter(object -> !object.key().isEmpty()).toList();
    }

    private static void line(Console out, String label, String value) {
        out.println("  " + String.format("%-12s", label) + " " + orDash(value));
    }

    /** A label, then one value per line under the first, aligned; {@code —} for none. */
    private static void lines(Console out, String label, List<String> values) {
        if (values.isEmpty()) {
            line(out, label, null);
            return;
        }
        line(out, label, values.get(0));
        values.stream().skip(1).forEach(value -> out.println("  " + " ".repeat(12) + " " + value));
    }

    /**
     * One {@code key=value} per tag, by key, without what the Job section already shows: the
     * project and type have their own lines, and the exclusion is a marker beside the status.
     */
    private static List<String> tags(Map<String, String> tags) {
        return new TreeMap<>(tags).entrySet().stream()
            .filter(e -> !e.getKey().equals(TagKeys.PROJECT) && !e.getKey().equals(TagKeys.TYPE))
            .filter(e -> !(e.getKey().equals(TagKeys.HIDDEN) && "true".equals(e.getValue())))
            .map(e -> Printable.of(e.getKey() + "=" + e.getValue()))
            .toList();
    }

    private static String trimmed(String path) {
        return path != null && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

}
