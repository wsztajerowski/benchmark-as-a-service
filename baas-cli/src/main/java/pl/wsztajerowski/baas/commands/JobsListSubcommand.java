package pl.wsztajerowski.baas.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import pl.wsztajerowski.baas.BaasApp;
import pl.wsztajerowski.baas.LoggingMixin;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.console.Table;
import pl.wsztajerowski.baas.console.Table.Column;
import pl.wsztajerowski.baas.infra.AwsClientFactory;
import pl.wsztajerowski.baas.infra.Ec2ProvisioningService;
import pl.wsztajerowski.baas.model.TagKeys;
import pl.wsztajerowski.baas.jobs.DynamoDbJobRecorder;
import pl.wsztajerowski.baas.jobs.JobListing;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
    name = "list",
    mixinStandardHelpOptions = true,
    description = "The most recent jobs of every project and status, newest first. A job whose "
        + "instance is gone without an outcome shows as vanished.",
    separator = " "
)
public class JobsListSubcommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(JobsListSubcommand.class);

    static final List<String> FORMATS = List.of("table", "json", "csv");

    private static final DateTimeFormatter STARTED =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC);

    private static final List<Column> COLUMNS = List.of(
        Column.left("JOB_ID", 28),
        Column.left("PROJECT", 24),
        Column.left("STATUS", 13),
        Column.left("SOURCE", 6),
        Column.left("INSTANCE", 19),
        Column.left("INSTANCE_TYPE", 13),
        Column.left("STARTED (UTC)", 16),
        Column.right("ELAPSED", 9));

    @Mixin LoggingMixin loggingMixin;

    @Spec CommandSpec spec;

    /** Set by tests; otherwise built from picocli's {@code getOut()} on first use. */
    Console console;

    @Option(names = "--limit", defaultValue = "20", description = "Maximum jobs to show (default 20).")
    int limit;

    @Option(names = "--in-flight", description = "Only jobs whose instance is pending or running.")
    boolean inFlight;

    @Option(names = "--project", description = "Only this project's jobs.")
    String project;

    @Option(names = "--tag", description = "Only jobs carrying this tag (key=value), repeatable; all must match.")
    Map<String, String> tags = new LinkedHashMap<>();

    @Option(names = "--format", defaultValue = "table", description = "Output format: table (default), json, csv.")
    String format;

    @Override
    public Integer call() {
        if (!FORMATS.contains(format.toLowerCase(Locale.ROOT))) {
            logger.error("Unknown --format '{}'. Valid: {}.", format, String.join(", ", FORMATS));
            return 2;
        }
        if (limit < 1) {
            logger.error("--limit must be at least 1.");
            return 2;
        }
        BaasConfig config = BaasApp.configService(spec).load();
        RunCommand.operatorCredentialsWarning(config).ifPresent(logger::warn);
        var factory = new AwsClientFactory(config.getAws().resolveRegion(), config.getAws().resolveOperatorProfile());

        // One DescribeInstances of the live runners decides every row: its size is bounded by what
        // is running, not by how many jobs vanished before.
        Map<String, String> live = new HashMap<>();
        try (var ec2 = factory.ec2()) {
            for (var runner : new Ec2ProvisioningService(ec2).listRunningBenchmarkInstances()) {
                if (runner.jobId() != null) {
                    live.put(runner.jobId(), runner.instanceId());
                }
            }
        }
        List<JobListing.Row> rows;
        try (var dynamoDb = factory.dynamoDb()) {
            rows = new DynamoDbJobRecorder(dynamoDb, config.resultsTable())
                .newestFirst(JobListing.filter(project, tags, inFlight, live), limit).stream()
                .map(job -> JobListing.resolve(job, live))
                .toList();
        }
        print(rows, Instant.now());
        return 0;
    }

    void print(List<JobListing.Row> rows, Instant now) {
        switch (format.toLowerCase(Locale.ROOT)) {
            case "json" -> printJson(rows);
            case "csv" -> printCsv(rows);
            default -> printTable(rows, now);
        }
    }

    /** Command payload, so it goes to the {@link Console}; an empty answer is payload too. */
    private void printTable(List<JobListing.Row> rows, Instant now) {
        var out = console();
        if (rows.isEmpty()) {
            out.println(inFlight ? "No jobs in flight." : "No jobs found.");
            return;
        }
        var table = new Table(out, 140, COLUMNS);
        table.printHeader();
        for (var row : rows) {
            var job = row.job();
            String instance = row.liveInstanceId() != null ? row.liveInstanceId() : job.instanceId();
            table.printRow(
                job.jobId(),
                job.project(),
                row.status(),
                orDash(job.tags().get(TagKeys.SOURCE)),
                orDash(instance),
                orDash(job.instanceType()),
                STARTED.format(job.createdAt()),
                row.inFlight() ? elapsed(Duration.between(job.createdAt(), now)) : "—");
            // A failed launch's reason, on its own line so no column moves for it.
            if (job.errorCode() != null) {
                out.println("  " + job.errorCode());
            }
        }
    }

    private void printJson(List<JobListing.Row> rows) {
        var out = console();
        out.println("[");
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            var job = row.job();
            out.printf("  {\"jobId\":%s,\"project\":%s,\"status\":%s,\"storedStatus\":%s,\"instanceId\":%s,"
                    + "\"instanceType\":%s,\"createdAt\":%s,\"resultPath\":%s,\"errorCode\":%s,\"tags\":%s}%s%n",
                json(job.jobId()), json(job.project()), json(row.status()), json(job.status()),
                json(row.liveInstanceId() != null ? row.liveInstanceId() : job.instanceId()),
                json(job.instanceType()), json(job.createdAt().toString()), json(job.resultPath()),
                json(job.errorCode()), ResultsQuerySubcommand.jsonObject(job.tags()), i < rows.size() - 1 ? "," : "");
        }
        out.println("]");
    }

    private void printCsv(List<JobListing.Row> rows) {
        var out = console();
        out.println("jobId,project,status,storedStatus,instanceId,instanceType,createdAt,resultPath,errorCode,tags");
        for (var row : rows) {
            var job = row.job();
            out.printf("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                job.jobId(), job.project(), row.status(), nullToEmpty(job.status()),
                nullToEmpty(row.liveInstanceId() != null ? row.liveInstanceId() : job.instanceId()),
                nullToEmpty(job.instanceType()), job.createdAt(), nullToEmpty(job.resultPath()),
                nullToEmpty(job.errorCode()), ResultsQuerySubcommand.csvField(ResultsQuerySubcommand.csvTags(job.tags())));
        }
    }

    static String elapsed(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        return RunCommand.formatElapsed(seconds);
    }

    private static String json(String value) {
        return value == null ? "null" : ResultsQuerySubcommand.jsonString(value);
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private Console console() {
        if (console == null) {
            console = Console.of(spec.commandLine().getOut());
        }
        return console;
    }
}
