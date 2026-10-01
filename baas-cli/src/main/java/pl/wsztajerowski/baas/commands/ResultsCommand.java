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
import pl.wsztajerowski.baas.config.ConfigService;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.infra.AwsClientFactory;
import pl.wsztajerowski.baas.results.ResultRow;
import pl.wsztajerowski.baas.results.ResultsFilters;
import pl.wsztajerowski.baas.results.ResultsGrouping;
import pl.wsztajerowski.baas.results.ResultsQueryService;
import pl.wsztajerowski.baas.results.ResultsTable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;

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

    /** The frame on screen, reprinted on the normal screen when {@code --watch} ends. */
    private String lastFrame;
    private boolean onAlternateScreen;

    /**
     * Reads one line of the operator's answer to the project picker. Set by tests; otherwise the
     * terminal, which {@link Console#interactive()} has already established is there.
     */
    Supplier<String> answerReader = () -> System.console().readLine();

    @Option(names = "--project", description = "Project partition to read. Without it: the current "
        + "directory's git repository if git.resolveProject is enabled, else a choice of projects.")
    String project;

    @Option(names = "--all-projects", description = "Report every project, with a PROJECT column. "
        + "Reads the whole table.")
    boolean allProjects;

    @Option(names = "--request-id", description = "Return every measurement of one run. Cannot be combined with other filters.")
    String requestId;

    @Option(names = "--benchmark-name", description = "Filter by benchmark name (regex).")
    String benchmarkName;

    @Option(names = "--tag", description = "Filter by tag, repeatable. Repeated tags must all match.")
    Map<String, String> tags;

    @Option(names = "--group-by", description = "Tag to group by when keeping the best score. Default: branch.",
        defaultValue = ResultsFilters.BRANCH)
    String groupBy;

    @Option(names = "--all-runs", description = "Report every measurement instead of the best per group, "
        + "including runs tagged exclude_from_results=true.")
    boolean allRuns;

    @Option(names = "--limit", description = "Maximum rows to report.")
    Integer limit;

    @Option(names = "--format", description = "Output format: table (default), json, csv.", defaultValue = "table")
    String format;

    @Option(names = "--watch", description = "Re-run the query every 30 s and redraw the table in place. "
        + "Needs an interactive terminal and the table format.")
    boolean watch;

    private ConfigService configService() {
        return BaasApp.configService(spec);
    }

    @Override
    public Integer call() throws InterruptedException {
        // Before the config is even read: a refused --watch must not reach AWS.
        String watchRefusal = watchRefusal();
        if (watchRefusal != null) {
            logger.error("{}", watchRefusal);
            return 2;
        }

        String conflict = requestIdConflict();
        if (conflict != null) {
            logger.error("--request-id names one run, so it cannot be combined with {}.", conflict);
            return 2;
        }
        if (project != null && allProjects) {
            logger.error("--project and --all-projects cannot be combined: name one project, or ask for all.");
            return 2;
        }

        BaasConfig config = configService().load();
        RunCommand.operatorCredentialsWarning(config).ifPresent(logger::warn);

        String tableName;
        try {
            tableName = config.resultsTable();
        } catch (IllegalStateException noInstallation) {
            logger.error("{}", noInstallation.getMessage());
            return 1;
        }

        var factory = new AwsClientFactory(
            config.getAws().getRegion(), config.getAws().resolveOperatorProfile());

        try (var results = new ResultsQueryService(factory.dynamoDb(), tableName)) {
            // Once, before --watch enters the alternate screen: a prompt cannot be answered there.
            if (requestId == null && !allProjects) {
                Optional<String> chosen = resolveProject(config, results);
                if (chosen.isEmpty()) {
                    return 1;
                }
                project = chosen.get();
            }
            if (watch) {
                watch(results);
            }
            List<ResultRow> rows = fetch(results, logger::warn, logger::info);

            switch (format.toLowerCase(Locale.ROOT)) {
                case "json" -> printJson(rows);
                case "csv" -> printCsv(rows);
                default -> printTable(console(), rows);
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
        List<ResultRow> rows;
        if (requestId != null) {
            rows = results.queryByRequestId(requestId);
        } else if (allProjects) {
            rows = results.scanAllProjects(allRuns);
        } else {
            rows = results.queryProject(project, allRuns);
        }

        if (requestId == null) {
            rows = ResultsFilters.byBenchmarkName(rows, benchmarkName);
            rows = ResultsFilters.byTags(rows, tags);
            ResultsFilters.unknownTagWarning(rows, tags).ifPresent(warn);
            if (!allRuns) {
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

    /**
     * Runs until interrupted (Ctrl+C), on the alternate screen. Leaving it restores the normal
     * screen and reprints the last frame there, so the result outlives the command. Left in a
     * {@code finally} for a failed query — its error must be logged on the normal screen, not lost
     * with the alternate one — and in a shutdown hook for Ctrl+C. {@link #leaveWatch} is
     * idempotent, so both firing is harmless.
     */
    private void watch(ResultsQueryService results) throws InterruptedException {
        var time = DateTimeFormatter.ofPattern("HH:mm:ss");
        enterWatch();
        Runtime.getRuntime().addShutdownHook(new Thread(this::leaveWatch));
        try {
            while (true) {
                var notes = new ArrayList<String>();
                List<ResultRow> rows = fetch(results, notes::add, notes::add);
                ResultsQueryService.environmentWarning(rows).ifPresent(notes::add);
                printFrame(rows, notes, LocalTime.now().format(time));
                Thread.sleep(WATCH_INTERVAL.toMillis());
            }
        } finally {
            leaveWatch();
        }
    }

    synchronized void enterWatch() {
        console().enterAlternateScreen();
        onAlternateScreen = true;
    }

    synchronized void leaveWatch() {
        if (!onAlternateScreen) {
            return;
        }
        onAlternateScreen = false;
        console().leaveAlternateScreen();
        if (lastFrame != null) {
            console().print(lastFrame);
        }
    }

    /**
     * One {@code --watch} frame: header, table, then the notes that would otherwise be logged —
     * in the frame, because logging them would repeat the same text every refresh and scroll the
     * table away. Rendered to a string first, so the same text can be reprinted on exit.
     */
    synchronized void printFrame(List<ResultRow> rows, List<String> notes, String refreshedAt) {
        var frame = new StringWriter();
        var out = console().renderingTo(new PrintWriter(frame));
        out.println(out.faint("Every " + WATCH_INTERVAL.toSeconds() + "s · refreshed " + refreshedAt
            + " · Ctrl+C to stop"));
        out.println("");
        printTable(out, rows);
        for (String note : notes) {
            out.println("");
            out.println(note);
        }
        lastFrame = frame.toString();
        console().showFrame(lastFrame);
    }

    private Console console() {
        if (console == null) {
            console = Console.of(spec.commandLine().getOut());
        }
        return console;
    }

    /**
     * {@code --request-id} reads a different index and returns one run whole; combining it with a
     * filter would silently ignore the filter, which reads as the filter being broken. A project is
     * refused too: one run is already narrower than any project, and a project that disagreed with
     * the run's own would be ignored without a word.
     */
    private String requestIdConflict() {
        if (requestId == null) {
            return null;
        }
        if (project != null) return "--project";
        if (benchmarkName != null) return "--benchmark-name";
        if (tags != null && !tags.isEmpty()) return "--tag";
        if (allProjects) return "--all-projects";
        return null;
    }

    /** The PROJECT column only when rows can come from more than one; tag lines only with {@code -v}. */
    private void printTable(Console out, List<ResultRow> rows) {
        ResultsTable.print(out, rows, allProjects, loggingMixin != null && loggingMixin.verbose());
    }

    /**
     * The project to read when none was named, in the order the operator would expect: the current
     * directory's repository when they opted into git derivation, else a choice on the terminal.
     * When nobody can answer a prompt — a pipe, a redirect, a machine format — the command fails
     * listing the projects rather than guessing or waiting on input that will never come. Empty
     * means it has already said why.
     */
    Optional<String> resolveProject(BaasConfig config, ResultsQueryService results) {
        if (project != null && !project.isBlank()) {
            return Optional.of(project);
        }
        if (config.getGit().isResolveProject()) {
            String derived = GitProject.repositoryName(Path.of(".").toAbsolutePath().normalize());
            if (derived != null) {
                return Optional.of(derived);
            }
        }
        List<String> projects = results.listVisibleProjects();
        if (projects.isEmpty()) {
            logger.error("No project holds results outside exclude_from_results. "
                + "To see everything: baas results --all-projects --all-runs");
            return Optional.empty();
        }
        if (!console().interactive() || !"table".equals(format.toLowerCase(Locale.ROOT))) {
            logger.error("No project named, and there is no terminal to choose one on. "
                + "Pass --project <name> or --all-projects. Projects: {}", String.join(", ", projects));
            return Optional.empty();
        }
        return pickProject(projects);
    }

    /** A numbered list and one line of input. Anything but a listed number is refused, not retried. */
    Optional<String> pickProject(List<String> projects) {
        var out = console();
        out.println("Projects with results:");
        for (int i = 0; i < projects.size(); i++) {
            out.println(String.format(Locale.ROOT, "  %2d) %s", i + 1, projects.get(i)));
        }
        out.println(out.faint("Tip: inside a repository, skip this with: baas config set --git-resolve-project true"));
        out.print("Choose a project [1-" + projects.size() + "]: ");
        String answer = answerReader.get();
        try {
            int choice = Integer.parseInt(answer == null ? "" : answer.strip());
            if (choice >= 1 && choice <= projects.size()) {
                return Optional.of(projects.get(choice - 1));
            }
        } catch (NumberFormatException notANumber) {
            // Falls through to the refusal below.
        }
        logger.error("'{}' is not one of the listed numbers. Nothing was queried.", answer);
        return Optional.empty();
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
            // Null for every run recorded before the prebaked-image change. They stay flat even
            // though `tags` now repeats them: CI and anyone else's `jq '.[0].imageVersion'` reads
            // them there.
            out.printf(
                "  {\"requestId\":\"%s\",\"benchmarkName\":\"%s\",\"benchmarkType\":\"%s\"," +
                "\"mode\":\"%s\",\"score\":%s,\"scoreError\":%s,\"scoreUnit\":\"%s\"," +
                "\"createdAt\":\"%s\",\"imageVersion\":%s,\"instanceType\":%s,\"tags\":%s}%s%n",
                r.requestId(), r.benchmarkName(), r.benchmarkType(), r.mode(),
                jsonNumber(r.score()), jsonNumber(r.scoreError()), r.scoreUnit(), r.createdAt(),
                jsonOrNull(r.imageVersion()), jsonOrNull(r.instanceType()), jsonObject(r.tags()),
                i < rows.size() - 1 ? "," : "");
        }
        out.println("]");
    }

    private void printCsv(List<ResultRow> rows) {
        var out = console();
        out.println(
            "requestId,benchmarkName,benchmarkType,mode,score,scoreError,scoreUnit,createdAt,imageVersion,instanceType,tags");
        for (ResultRow r : rows) {
            // Locale.ROOT for the same reason as printJson — a comma decimal separator turns one
            // CSV column into two.
            out.printf("%s,%s,%s,%s,%.6f,%.6f,%s,%s,%s,%s,%s%n",
                r.requestId(), r.benchmarkName(), r.benchmarkType(), r.mode(),
                r.score(), r.scoreError(), r.scoreUnit(), r.createdAt(),
                r.imageVersion() != null ? r.imageVersion() : "",
                r.instanceType() != null ? r.instanceType() : "",
                csvField(csvTags(r.tags())));
        }
    }

    /** Every tag, key-sorted, as {@code k=v;k=v}. */
    static String csvTags(Map<String, String> tags) {
        return new java.util.TreeMap<>(tags).entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(java.util.stream.Collectors.joining(";"));
    }

    /**
     * RFC 4180 quoting. Tag values are free-form — the first CSV field that can carry a comma, a
     * quote or a line break — so this one is always quoted, embedded quotes doubled.
     */
    static String csvField(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    /** Key-sorted, so the same row always serialises identically. */
    static String jsonObject(Map<String, String> tags) {
        return new java.util.TreeMap<>(tags).entrySet().stream()
            .map(e -> jsonString(e.getKey()) + ":" + jsonString(e.getValue()))
            .collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }

    /** Escapes what JSON requires; tag values are free-form, unlike the other fields. */
    static String jsonString(String value) {
        var out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
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
