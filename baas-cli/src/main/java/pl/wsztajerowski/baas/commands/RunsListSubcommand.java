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
import pl.wsztajerowski.baas.runs.DynamoDbRunRecorder;
import pl.wsztajerowski.baas.runs.RunListing;

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
    description = "The most recent runs of every project and status, newest first. A run whose "
        + "instance is gone without an outcome shows as vanished.",
    separator = " "
)
public class RunsListSubcommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(RunsListSubcommand.class);

    static final List<String> FORMATS = List.of("table", "json", "csv");

    private static final DateTimeFormatter STARTED =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC);

    private static final List<Column> COLUMNS = List.of(
        Column.left("RUN_ID", 28),
        Column.left("PROJECT", 20),
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

    @Option(names = "--limit", defaultValue = "20", description = "Maximum runs to show (default 20).")
    int limit;

    @Option(names = "--in-flight", description = "Only runs whose instance is pending or running.")
    boolean inFlight;

    @Option(names = "--project", description = "Only this project's runs.")
    String project;

    @Option(names = "--tag", description = "Only runs carrying this tag (key=value), repeatable; all must match.")
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
        // is running, not by how many runs vanished before.
        Map<String, String> live = new HashMap<>();
        try (var ec2 = factory.ec2()) {
            for (var runner : new Ec2ProvisioningService(ec2).listRunningBenchmarkInstances()) {
                if (runner.runId() != null) {
                    live.put(runner.runId(), runner.instanceId());
                }
            }
        }
        List<RunListing.Row> rows;
        try (var dynamoDb = factory.dynamoDb()) {
            rows = new DynamoDbRunRecorder(dynamoDb, config.resultsTable())
                .newestFirst(RunListing.filter(project, tags, inFlight, live), limit).stream()
                .map(run -> RunListing.resolve(run, live))
                .toList();
        }
        print(rows, Instant.now());
        return 0;
    }

    void print(List<RunListing.Row> rows, Instant now) {
        switch (format.toLowerCase(Locale.ROOT)) {
            case "json" -> printJson(rows);
            case "csv" -> printCsv(rows);
            default -> printTable(rows, now);
        }
    }

    /** Command payload, so it goes to the {@link Console}; an empty answer is payload too. */
    private void printTable(List<RunListing.Row> rows, Instant now) {
        var out = console();
        if (rows.isEmpty()) {
            out.println(inFlight ? "No runs in flight." : "No runs found.");
            return;
        }
        var table = new Table(out, 136, COLUMNS);
        table.printHeader();
        for (var row : rows) {
            var run = row.run();
            String instance = row.liveInstanceId() != null ? row.liveInstanceId() : run.instanceId();
            table.printRow(
                run.runId(),
                run.project(),
                row.status(),
                orDash(run.tags().get(TagKeys.SOURCE)),
                orDash(instance),
                orDash(run.instanceType()),
                STARTED.format(run.createdAt()),
                row.inFlight() ? elapsed(Duration.between(run.createdAt(), now)) : "—");
            // A failed launch's reason, on its own line so no column moves for it.
            if (run.errorCode() != null) {
                out.println("  " + run.errorCode());
            }
        }
    }

    private void printJson(List<RunListing.Row> rows) {
        var out = console();
        out.println("[");
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            var run = row.run();
            out.printf("  {\"runId\":%s,\"project\":%s,\"status\":%s,\"storedStatus\":%s,\"instanceId\":%s,"
                    + "\"instanceType\":%s,\"createdAt\":%s,\"resultPath\":%s,\"errorCode\":%s,\"tags\":%s}%s%n",
                json(run.runId()), json(run.project()), json(row.status()), json(run.status()),
                json(row.liveInstanceId() != null ? row.liveInstanceId() : run.instanceId()),
                json(run.instanceType()), json(run.createdAt().toString()), json(run.resultPath()),
                json(run.errorCode()), ResultsCommand.jsonObject(run.tags()), i < rows.size() - 1 ? "," : "");
        }
        out.println("]");
    }

    private void printCsv(List<RunListing.Row> rows) {
        var out = console();
        out.println("runId,project,status,storedStatus,instanceId,instanceType,createdAt,resultPath,errorCode,tags");
        for (var row : rows) {
            var run = row.run();
            out.printf("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                run.runId(), run.project(), row.status(), nullToEmpty(run.status()),
                nullToEmpty(row.liveInstanceId() != null ? row.liveInstanceId() : run.instanceId()),
                nullToEmpty(run.instanceType()), run.createdAt(), nullToEmpty(run.resultPath()),
                nullToEmpty(run.errorCode()), ResultsCommand.csvField(ResultsCommand.csvTags(run.tags())));
        }
    }

    static String elapsed(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        return RunCommand.formatElapsed(seconds);
    }

    private static String json(String value) {
        return value == null ? "null" : ResultsCommand.jsonString(value);
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
