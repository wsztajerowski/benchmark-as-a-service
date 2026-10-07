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
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.infra.AwsClientFactory;
import pl.wsztajerowski.baas.infra.Ec2ProvisioningService;
import pl.wsztajerowski.baas.infra.S3UploadService;
import pl.wsztajerowski.baas.jobs.DynamoDbJobRecorder;
import pl.wsztajerowski.baas.jobs.JobListing;
import pl.wsztajerowski.baas.model.JobItem;
import pl.wsztajerowski.baas.results.EnvironmentManifest;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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

    /** Set by tests; otherwise built from picocli's {@code getOut()} on first use. */
    Console console;

    @Parameters(index = "0", paramLabel = "<jobId>",
        description = "The job identifier `baas run` printed and `baas jobs list` shows.")
    String jobId;

    @Option(names = "--format", defaultValue = "table", description = "Output format: table (default), json.")
    String format;

    /** Where a job's parts are read from. Replaced by tests, which have no AWS. */
    interface Sources extends AutoCloseable {
        Optional<JobItem> job(String jobId);

        Optional<String> liveInstance(String jobId);

        Optional<String> manifest(String resultPath);

        List<String> keys(String resultPath);

        @Override
        void close();
    }

    Sources sources(BaasConfig config) {
        var factory = new AwsClientFactory(config.getAws().resolveRegion(), config.getAws().resolveOperatorProfile());
        var dynamoDb = factory.dynamoDb();
        var ec2 = factory.ec2();
        var s3 = factory.s3();
        var recorder = new DynamoDbJobRecorder(dynamoDb, config.resultsTable());
        var instances = new Ec2ProvisioningService(ec2);
        var storage = new S3UploadService(s3);
        String bucket = config.bucket();
        return new Sources() {
            public Optional<JobItem> job(String id) { return recorder.find(id); }
            public Optional<String> liveInstance(String id) { return instances.findLive(id); }
            public Optional<String> manifest(String path) { return storage.getObjectIfExists(bucket, path + "/environment.json"); }
            public List<String> keys(String path) { return storage.listKeys(bucket, path + "/"); }
            public void close() {
                dynamoDb.close();
                ec2.close();
                s3.close();
            }
        };
    }

    @Override
    public Integer call() {
        String format = this.format.toLowerCase(Locale.ROOT);
        if (!List.of("table", "json").contains(format)) {
            logger.error("Unknown --format '{}'. Valid: table, json.", this.format);
            return 2;
        }
        String notAJobId = JobReference.notAJobId(jobId);
        if (notAJobId != null) {
            logger.error("{}", notAJobId);
            return 2;
        }
        BaasConfig config = BaasApp.configService(spec).load();
        RunCommand.operatorCredentialsWarning(config).ifPresent(logger::warn);

        try (var sources = sources(config)) {
            Optional<JobItem> found = sources.job(jobId);
            if (found.isEmpty()) {
                // Before any S3 read: an unknown id names no prefix to read.
                logger.error("{}", JobReference.noSuchJob(jobId));
                return 1;
            }
            JobItem job = found.get();
            Map<String, String> live = new LinkedHashMap<>();
            if (!job.isTerminal()) {
                sources.liveInstance(jobId).ifPresent(instance -> live.put(jobId, instance));
            }
            JobListing.Row row = JobListing.resolve(job, live);
            String path = trimmed(job.resultPath());
            Optional<String> manifestJson = sources.manifest(path);
            Optional<EnvironmentManifest> manifest = manifestJson.map(json -> EnvironmentManifest.parse(jobId, json));
            List<String> artifacts = relative(sources.keys(path), path);

            if (format.equals("json")) {
                printJson(row, manifestJson.orElse("null"), artifacts);
            } else {
                printView(console(), row, manifest, artifacts);
            }
        }
        return 0;
    }

    void printView(Console out, JobListing.Row row, Optional<EnvironmentManifest> manifest, List<String> artifacts) {
        JobItem job = row.job();
        String status = row.status().equals(job.status()) ? row.status()
            : row.status() + " (stored: " + job.status() + ")";
        out.println("Job " + job.jobId() + "   " + status);
        line(out, "Project", job.project());
        line(out, "Type", job.tags().get("type"));
        line(out, "Created", CREATED.format(job.createdAt()) + " UTC");
        String instance = row.liveInstanceId() != null ? row.liveInstanceId() : job.instanceId();
        line(out, "Instance", (instance == null ? "—" : instance) + " · " + orDash(job.instanceType()));
        line(out, "Result path", trimmed(job.resultPath()) + "/");
        if (job.errorCode() != null) {
            line(out, "Error", job.errorCode());
        }
        line(out, "Tags", tags(job.tags()));

        out.println("");
        if (manifest.isEmpty()) {
            out.println("Environment   none — the instance never booted, or wrote no manifest");
        } else {
            out.println("Environment (schema " + manifest.get().schemaVersion().orElse("?") + ")");
            for (String group : EnvironmentManifest.GROUPS) {
                Map<String, String> fields = manifest.get().group(group);
                if (!fields.isEmpty()) {
                    line(out, group, String.join(" · ", fields.entrySet().stream()
                        .map(e -> e.getKey() + " " + orDash(e.getValue())).toList()));
                }
            }
        }

        out.println("");
        if (artifacts.isEmpty()) {
            out.println("Artifacts   none");
        } else {
            out.println("Artifacts (" + artifacts.size() + ")");
            out.println("  " + String.join(" · ", byFolder(artifacts)));
        }
    }

    private void printJson(JobListing.Row row, String environmentJson, List<String> artifacts) {
        JobItem job = row.job();
        var out = console();
        String jobJson = "{\"jobId\":%s,\"project\":%s,\"status\":%s,\"storedStatus\":%s,\"instanceId\":%s,"
            .formatted(json(job.jobId()), json(job.project()), json(row.status()), json(job.status()),
                json(row.liveInstanceId() != null ? row.liveInstanceId() : job.instanceId()))
            + "\"instanceType\":%s,\"createdAt\":%s,\"resultPath\":%s,\"errorCode\":%s,\"tags\":%s}"
            .formatted(json(job.instanceType()), json(job.createdAt().toString()), json(job.resultPath()),
                json(job.errorCode()), ResultsQuerySubcommand.jsonObject(job.tags()));
        String artifactsJson = artifacts.stream().map(JobsShowSubcommand::json)
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        out.println("{\"job\":" + jobJson + ",\"environment\":" + environmentJson.strip()
            + ",\"artifacts\":" + artifactsJson + "}");
    }

    /** Top-level files by name, folders as {@code name/ (n)}: what is there, without every key. */
    static List<String> byFolder(List<String> relativeKeys) {
        Map<String, Integer> folders = new TreeMap<>();
        List<String> files = new ArrayList<>();
        for (String key : relativeKeys) {
            int slash = key.indexOf('/');
            if (slash < 0) {
                files.add(key);
            } else {
                folders.merge(key.substring(0, slash + 1), 1, Integer::sum);
            }
        }
        List<String> summary = new ArrayList<>(files.stream().sorted().toList());
        folders.forEach((folder, count) -> summary.add(folder + " (" + count + ")"));
        return summary;
    }

    private static List<String> relative(List<String> keys, String path) {
        String prefix = path + "/";
        return keys.stream().map(key -> key.startsWith(prefix) ? key.substring(prefix.length()) : key)
            .filter(key -> !key.isEmpty()).toList();
    }

    private static void line(Console out, String label, String value) {
        out.println("  " + String.format("%-12s", label) + " " + orDash(value));
    }

    private static String tags(Map<String, String> tags) {
        return tags.isEmpty() ? "—" : String.join("  ", new TreeMap<>(tags).entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue()).toList());
    }

    private static String trimmed(String path) {
        return path != null && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String json(String value) {
        return value == null ? "null" : ResultsQuerySubcommand.jsonString(value);
    }

    private Console console() {
        if (console == null) {
            console = Console.of(spec.commandLine().getOut());
        }
        return console;
    }
}
