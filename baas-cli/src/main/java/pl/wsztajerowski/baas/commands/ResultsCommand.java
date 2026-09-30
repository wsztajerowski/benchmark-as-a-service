package pl.wsztajerowski.baas.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import pl.wsztajerowski.baas.LoggingMixin;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.config.ConfigService;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.infra.AwsClientFactory;
import pl.wsztajerowski.baas.results.ResultRow;
import pl.wsztajerowski.baas.results.ResultsFilters;
import pl.wsztajerowski.baas.results.ResultsGrouping;
import pl.wsztajerowski.baas.results.ResultsQueryService;
import pl.wsztajerowski.baas.results.ResultsTable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

@Command(
    name = "results",
    mixinStandardHelpOptions = true,
    description = "Query benchmark results from the results table."
)
public class ResultsCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(ResultsCommand.class);

    /**
     * Fixed: a run lands minutes after the previous one, and each refresh is one partition
     * {@code Query}. An option can be added when someone needs a different pace.
     */
    static final Duration WATCH_INTERVAL = Duration.ofSeconds(30);

    @Mixin LoggingMixin loggingMixin;

    @Spec CommandSpec spec;

    /** Set by tests; otherwise built from picocli's {@code getOut()} on first use. */
    Console console;

    /**
     * Addresses another installation's results table for this invocation only, and persists
     * nothing. This is how a retired installation's history stays readable after its stack is
     * gone. Read-only by design: no command that writes measurements accepts it, so a machine
     * cannot be left quietly recording new runs into an archive.
     */
    @Option(names = "--results-table",
        description = "Read from this results table instead of the configured installation's. "
            + "Not persisted.")
    String resultsTableOverride;

    @Option(names = "--project", description = "Project partition to read. Defaults to the git repository name.")
    String project;

    @Option(names = "--request-id", description = "Return every measurement of one run. Cannot be combined with other filters.")
    String requestId;

    @Option(names = "--benchmark-name", description = "Filter by benchmark name (regex).")
    String benchmarkName;

    @Option(names = "--tag", description = "Filter by tag, repeatable. Repeated tags must all match.")
    Map<String, String> tags;

    @Option(names = "--living-branches", description = "Filter by branches present in current git repo.")
    boolean livingBranches;

    @Option(names = "--group-by", description = "Tag to group by when keeping the best score. Default: branch.",
        defaultValue = ResultsFilters.BRANCH)
    String groupBy;

    @Option(names = "--all", description = "Report every measurement instead of the best per group.")
    boolean all;

    @Option(names = "--limit", description = "Maximum rows to report.")
    Integer limit;

    @Option(names = "--format", description = "Output format: table (default), json, csv.", defaultValue = "table")
    String format;

    @Option(names = "--watch", description = "Re-run the query every 30 s and redraw the table in place. "
        + "Needs an interactive terminal and the table format.")
    boolean watch;

    private final ConfigService configService = new ConfigService();

    @Override
    public Integer call() throws InterruptedException {
        // Before the config is even read: a refused --watch must not reach AWS.
        String watchRefusal = watchRefusal();
        if (watchRefusal != null) {
            logger.error("{}", watchRefusal);
            return 2;
        }

        BaasConfig config = configService.load();
        RunCommand.operatorCredentialsWarning(config).ifPresent(logger::warn);

        String conflict = requestIdConflict();
        if (conflict != null) {
            logger.error("--request-id names one run, so it cannot be combined with {}.", conflict);
            return 2;
        }

        String tableName;
        try {
            tableName = resultsTableOverride != null ? resultsTableOverride : config.resultsTable();
        } catch (IllegalStateException noInstallation) {
            logger.error("{}", noInstallation.getMessage());
            return 1;
        }

        var factory = new AwsClientFactory(
            config.getAws().getRegion(), config.getAws().resolveOperatorProfile());

        try (var results = new ResultsQueryService(factory.dynamoDb(), tableName)) {
            if (watch) {
                watch(results);
            }
            List<ResultRow> rows = fetch(results, logger::warn, logger::info);

            switch (format.toLowerCase(Locale.ROOT)) {
                case "json" -> printJson(rows);
                case "csv" -> printCsv(rows);
                default -> ResultsTable.print(console(), rows);
            }
            ResultsQueryService.environmentWarning(rows).ifPresent(logger::warn);
        }
        return 0;
    }

    /**
     * The rows one invocation reports. Notes about them — an unknown tag, a {@code --limit} cut —
     * go to the given sinks: the logger normally, the frame under {@code --watch}.
     */
    private List<ResultRow> fetch(ResultsQueryService results, Consumer<String> warn, Consumer<String> info) {
        List<ResultRow> rows = requestId != null
            ? results.queryByRequestId(requestId)
            : results.queryProject(resolveProject());

        if (requestId == null) {
            rows = ResultsFilters.byBenchmarkName(rows, benchmarkName);
            rows = ResultsFilters.byTags(rows, tags);
            if (livingBranches) {
                rows = ResultsFilters.byLivingBranches(rows, gitRemoteBranches());
            }
            ResultsFilters.unknownTagWarning(rows, tags).ifPresent(warn);
            if (!all) {
                rows = ResultsGrouping.bestPerGroup(rows, groupBy);
            }
        }

        rows = ResultsGrouping.sortedForDisplay(rows);
        if (limit != null && limit >= 0 && rows.size() > limit) {
            info.accept("Reporting " + limit + " of " + rows.size() + " rows (--limit).");
            rows = rows.subList(0, limit);
        }
        return rows;
    }

    /**
     * Refused rather than degraded: without a terminal there is nothing to redraw, and a pipe
     * receiving a new table every 30 s forever is not output anyone asked for.
     */
    String watchRefusal() {
        if (!watch) {
            return null;
        }
        if (!"table".equals(format.toLowerCase(Locale.ROOT))) {
            return "--watch applies to the table only; it cannot be combined with --format " + format + ".";
        }
        if (!console().interactive()) {
            return "--watch needs an interactive terminal; standard output is redirected or piped.";
        }
        return null;
    }

    /** Runs until interrupted (Ctrl+C); the last frame stays on screen. */
    private void watch(ResultsQueryService results) throws InterruptedException {
        var time = DateTimeFormatter.ofPattern("HH:mm:ss");
        while (true) {
            var notes = new ArrayList<String>();
            List<ResultRow> rows = fetch(results, notes::add, notes::add);
            ResultsQueryService.environmentWarning(rows).ifPresent(notes::add);
            printFrame(rows, notes, LocalTime.now().format(time));
            Thread.sleep(WATCH_INTERVAL.toMillis());
        }
    }

    /**
     * One {@code --watch} frame: clear, header, table, then the notes that would otherwise be
     * logged — in the frame, because logging them would repeat the same text every refresh and
     * scroll the table away.
     */
    void printFrame(List<ResultRow> rows, List<String> notes, String refreshedAt) {
        var out = console();
        out.clearScreen();
        out.println(out.faint("Every " + WATCH_INTERVAL.toSeconds() + "s · refreshed " + refreshedAt
            + " · Ctrl+C to stop"));
        out.println("");
        ResultsTable.print(out, rows);
        for (String note : notes) {
            out.println("");
            out.println(note);
        }
    }

    private Console console() {
        if (console == null) {
            console = Console.of(spec.commandLine().getOut());
        }
        return console;
    }

    /**
     * {@code --request-id} reads a different index and returns one run whole; combining it with a
     * filter would silently ignore the filter, which reads as the filter being broken.
     */
    private String requestIdConflict() {
        if (requestId == null) {
            return null;
        }
        if (benchmarkName != null) return "--benchmark-name";
        if (tags != null && !tags.isEmpty()) return "--tag";
        if (livingBranches) return "--living-branches";
        return null;
    }

    /** Same derivation as {@code baas run}: the partition follows the project you are sitting in. */
    private String resolveProject() {
        if (project != null && !project.isBlank()) {
            return project;
        }
        String derived = GitProject.repositoryName(Path.of(".").toAbsolutePath().normalize());
        if (derived == null) {
            throw new IllegalStateException(
                "Cannot determine the project name: not inside a git repository. Pass --project <name>.");
        }
        return derived;
    }

    private List<String> gitRemoteBranches() {
        try {
            var pb = new ProcessBuilder("git", "branch", "-r").redirectErrorStream(true);
            var proc = pb.start();
            String out = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();
            return out.lines()
                .map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("origin/HEAD"))
                .map(l -> l.replace("origin/", ""))
                .toList();
        } catch (IOException | InterruptedException e) {
            return List.of();
        }
    }

    /**
     * Result payloads go to the {@link Console}, never the logger: {@code baas results --format
     * json | jq} has to see clean JSON, and SimpleLogger writes to stderr with a timestamp on
     * every line. Same reasoning covers {@link #printCsv} and {@link ResultsTable}. Never coloured.
     */
    private void printJson(List<ResultRow> rows) {
        var out = console();
        out.println("[");
        for (int i = 0; i < rows.size(); i++) {
            ResultRow r = rows.get(i);
            // imageVersion/instanceType ride along so a machine consumer can tell comparable rows
            // from incomparable ones — the table says so in prose, and `| jq` cannot read prose.
            // Null for every run recorded before the prebaked-image change.
            out.printf(
                "  {\"requestId\":\"%s\",\"benchmarkName\":\"%s\",\"benchmarkType\":\"%s\"," +
                "\"mode\":\"%s\",\"score\":%s,\"scoreError\":%s,\"scoreUnit\":\"%s\"," +
                "\"createdAt\":\"%s\",\"imageVersion\":%s,\"instanceType\":%s}%s%n",
                r.requestId(), r.benchmarkName(), r.benchmarkType(), r.mode(),
                jsonNumber(r.score()), jsonNumber(r.scoreError()), r.scoreUnit(), r.createdAt(),
                jsonOrNull(r.imageVersion()), jsonOrNull(r.instanceType()),
                i < rows.size() - 1 ? "," : "");
        }
        out.println("]");
    }

    private void printCsv(List<ResultRow> rows) {
        var out = console();
        out.println(
            "requestId,benchmarkName,benchmarkType,mode,score,scoreError,scoreUnit,createdAt,imageVersion,instanceType");
        for (ResultRow r : rows) {
            // Locale.ROOT for the same reason as printJson — a comma decimal separator turns one
            // CSV column into two.
            out.printf("%s,%s,%s,%s,%.6f,%.6f,%s,%s,%s,%s%n",
                r.requestId(), r.benchmarkName(), r.benchmarkType(), r.mode(),
                r.score(), r.scoreError(), r.scoreUnit(), r.createdAt(),
                r.imageVersion() != null ? r.imageVersion() : "",
                r.instanceType() != null ? r.instanceType() : "");
        }
    }

    /** A missing tag is JSON null, not the string "null" — the two mean different things here. */
    private static String jsonOrNull(String value) {
        return value != null ? "\"" + value + "\"" : "null";
    }

    /**
     * JSON has no NaN or Infinity literal, and a single-iteration JMH run reports {@code NaN}
     * score error routinely — emitting it produces a document {@code jq} refuses outright.
     */
    private static String jsonNumber(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.6f", value) : "null";
    }
}
