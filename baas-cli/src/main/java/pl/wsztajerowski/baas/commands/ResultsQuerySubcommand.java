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
import pl.wsztajerowski.baas.console.Watch;
import pl.wsztajerowski.baas.console.Hints;
import pl.wsztajerowski.baas.infra.AwsClientFactory;
import pl.wsztajerowski.baas.results.ResultRow;
import pl.wsztajerowski.baas.results.ResultsFilters;
import pl.wsztajerowski.baas.results.ResultsGrouping;
import pl.wsztajerowski.baas.results.ResultsQueryService;
import pl.wsztajerowski.baas.results.ResultsTable;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Command(
    name = "query",
    mixinStandardHelpOptions = true,
    description = "Query benchmark results from the results table."
)
public class ResultsQuerySubcommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(ResultsQuerySubcommand.class);

    /**
     * Fixed: a job lands minutes after the previous one, and each refresh is one partition
     * {@code Query}. An option can be added when someone needs a different pace.
     */
    static final Duration WATCH_INTERVAL = Watch.INTERVAL;

    @Mixin LoggingMixin loggingMixin;

    @Spec CommandSpec spec;

    /** Set by tests; otherwise built from picocli's {@code getOut()} on first use. */
    Console console;

    private Watch watcher;

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

    @Option(names = "--job-id", description = "Only this job's measurements, read through the job-ID index. "
        + "Combines with every filter except --project and --all-projects.")
    String jobId;

    @Option(names = "--benchmark-name", description = "Filter by benchmark name (regex).")
    String benchmarkName;

    @Option(names = "--tag", description = "Keep rows carrying this tag (key=value), repeatable; all must match.")
    Map<String, String> tags;

    @Option(names = "--exclude-tag", paramLabel = "<key=value>",
        description = "Drop rows carrying this tag, repeatable; any match drops the row.")
    List<String> excludeTags;

    @Option(names = "--best-per", paramLabel = "<tag>",
        description = "Keep only the best score per benchmark, mode and value of this tag (e.g. branch): "
            + "the highest for throughput, the lowest for time per operation.")
    String bestPer;

    @Option(names = "--show-excluded",
        description = "Also show measurements tagged exclude_from_results=true (drawn faint on a terminal).")
    boolean showExcluded;

    @Option(names = "--sort-by", paramLabel = "<field>",
        description = "Order by created (default, newest first), benchmark, score or project.")
    String sortBy = "created";

    @Option(names = "--asc", description = "Reverse the order: oldest, lowest or alphabetical first.")
    boolean ascending;

    @Option(names = "--offset", paramLabel = "<n>", description = "Skip this many rows first (default 0).")
    int offset;

    @Option(names = "--limit", paramLabel = "<n>",
        description = "Report at most this many rows (default 20, or no limit with --job-id; 0 for no limit).")
    Integer limit;

    /** The default cut, for sweeps of a project or of every project. */
    static final int DEFAULT_LIMIT = 20;

    /**
     * The limit in force: the one given, else none for a job lookup — a job's measurements are a
     * bounded set, and {@code baas run} points at exactly this lookup — else {@link #DEFAULT_LIMIT}.
     */
    int effectiveLimit() {
        if (limit != null) {
            return limit;
        }
        return jobId != null ? 0 : DEFAULT_LIMIT;
    }

    @Option(names = "--format", description = "Output format: table (default), json, csv.", defaultValue = "table")
    String format;

    @Option(names = "--watch", description = "Re-run the query every 30 s and redraw the table in place. "
        + "Needs an interactive terminal and the table format.")
    boolean watch;

    private ConfigService configService() {
        return BaasApp.configService(spec);
    }

    /** Every other value used to fall through to the table, so a typo looked like a success. */
    static final List<String> FORMATS = List.of("table", "json", "csv");

    @Override
    public Integer call() throws InterruptedException {
        if (!FORMATS.contains(format.toLowerCase(Locale.ROOT))) {
            logger.error("Unknown --format '{}'. Valid: {}.", format, String.join(", ", FORMATS));
            return 2;
        }
        // Before the config is even read: a refused --watch must not reach AWS.
        String watchRefusal = watchRefusal();
        if (watchRefusal != null) {
            logger.error("{}", watchRefusal);
            return 2;
        }

        if (effectiveLimit() < 0 || offset < 0) {
            logger.error("--limit and --offset must not be negative; got {} and {}.", effectiveLimit(), offset);
            return 2;
        }
        if (!ResultsGrouping.SORT_FIELDS.contains(sortBy)) {
            logger.error("Unknown --sort-by '{}'. Valid: {}.", sortBy, String.join(", ", ResultsGrouping.SORT_FIELDS));
            return 2;
        }
        if (bestPer != null && bestPer.isBlank()) {
            logger.error("--best-per needs a tag to group by, e.g. --best-per branch.");
            return 2;
        }
        try {
            if (excludeTags != null) {
                excludeTags.forEach(ResultsFilters::pair);
            }
        } catch (IllegalArgumentException bad) {
            logger.error("--exclude-tag: {}", bad.getMessage());
            return 2;
        }
        String conflict = jobIdConflict();
        if (conflict != null) {
            logger.error("--job-id reads one job through its own index, so it cannot be combined with {}.", conflict);
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
        } catch (IllegalStateException noDeployment) {
            logger.error("{}", noDeployment.getMessage());
            return 1;
        }

        try (var results = openResults(config, tableName)) {
            // Once, before --watch enters the alternate screen: a prompt cannot be answered there.
            if (jobId == null && !allProjects) {
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
            if (rows.isEmpty() && !allProjects) {
                Hints.show(console(), logger, "which projects have results", "baas query --all-projects");
            }
        }
        return 0;
    }

    /** The query service for the configured table. Overridden by tests, which have no table. */
    ResultsQueryService openResults(BaasConfig config, String tableName) {
        var factory = new AwsClientFactory(
            config.getAws().resolveRegion(), config.getAws().resolveOperatorProfile());
        return new ResultsQueryService(factory.dynamoDb(), tableName);
    }

    /**
     * The rows one invocation reports. Notes about them — an unknown tag, a {@code --limit} cut —
     * go to the given sinks: the logger normally, the frame under {@code --watch}.
     */
    private List<ResultRow> fetch(ResultsQueryService results, Consumer<String> warn, Consumer<String> info) {
        List<ResultRow> rows;
        // A lookup by job id is a request for that job, so the exclusion never applies to it.
        if (jobId != null) {
            rows = results.queryByJobId(jobId);
        } else if (allProjects) {
            rows = results.scanAllProjects(showExcluded);
        } else {
            rows = results.queryProject(project, showExcluded);
        }

        // The shared pipeline: filter, [--best-per], sort, --offset, --limit.
        rows = ResultsFilters.byBenchmarkName(rows, benchmarkName);
        rows = ResultsFilters.byTags(rows, tags);
        rows = ResultsFilters.byExcludedTags(rows, excludeTags, ResultRow::tag);
        ResultsFilters.unknownTagWarning(rows, tags).ifPresent(warn);
        if (bestPer != null) {
            rows = ResultsGrouping.bestPerGroup(rows, bestPer);
        }
        rows = ResultsGrouping.sorted(rows, sortBy, ascending);
        int total = rows.size();
        rows = rows.stream().skip(offset).toList();
        int limit = effectiveLimit();
        if (limit > 0 && rows.size() > limit) {
            rows = rows.subList(0, limit);
        }
        if (rows.size() < total) {
            info.accept("Reporting " + rows.size() + " of " + total + " rows"
                + (offset > 0 ? " from offset " + offset : "") + " (--limit " + limit + ").");
        }
        return rows;
    }

    String watchRefusal() {
        return watch ? Watch.refusal(format, console()) : null;
    }

    private void watch(ResultsQueryService results) throws InterruptedException {
        watcher().run(out -> renderFrame(out, results));
    }

    private void renderFrame(Console out, ResultsQueryService results) {
        var notes = new ArrayList<String>();
        List<ResultRow> rows = fetch(results, notes::add, notes::add);
        renderRows(out, rows, notes);
    }

    private void renderRows(Console out, List<ResultRow> rows, List<String> notes) {
        printTable(out, rows);
        for (String note : notes) {
            out.println("");
            out.println(note);
        }
    }

    private Watch watcher() {
        if (watcher == null) {
            watcher = new Watch(console());
        }
        return watcher;
    }

    void enterWatch() {
        watcher().enter();
    }

    void leaveWatch() {
        watcher().leave();
    }

    void printFrame(List<ResultRow> rows, List<String> notes, String refreshedAt) {
        watcher().show(out -> renderRows(out, rows, notes), refreshedAt);
    }

    private Console console() {
        if (console == null) {
            console = Console.of(spec.commandLine().getOut());
        }
        return console;
    }

    /**
     * {@code --job-id} is an ordinary filter, but it reads the job-ID index instead of a project's
     * partition, so the two options that choose a partition are the ones it cannot take: a project
     * that disagreed with the job's own would be ignored without a word.
     */
    private String jobIdConflict() {
        if (jobId == null) {
            return null;
        }
        if (project != null) return "--project";
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
                + "To see everything: baas results query --all-projects --show-excluded");
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
     * Result payloads go to the {@link Console}, never the logger: {@code baas results query --format
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
            // Null for every job recorded before the prebaked-image change. They stay flat even
            // though `tags` now repeats them: CI and anyone else's `jq '.[0].imageVersion'` reads
            // them there.
            out.printf(
                "  {\"jobId\":\"%s\",\"benchmarkName\":\"%s\",\"benchmarkType\":\"%s\"," +
                "\"mode\":\"%s\",\"score\":%s,\"scoreError\":%s,\"scoreUnit\":\"%s\"," +
                "\"createdAt\":\"%s\",\"imageVersion\":%s,\"instanceType\":%s,\"tags\":%s,\"params\":%s}%s%n",
                r.jobId(), r.benchmarkName(), r.benchmarkType(), r.mode(),
                jsonNumber(r.score()), jsonNumber(r.scoreError()), r.scoreUnit(), r.createdAt(),
                jsonOrNull(r.imageVersion()), jsonOrNull(r.instanceType()), jsonObject(r.tags()),
                jsonObject(r.params()), i < rows.size() - 1 ? "," : "");
        }
        out.println("]");
    }

    private void printCsv(List<ResultRow> rows) {
        var out = console();
        out.println(
            // params last, so a consumer reading columns by position is not shifted.
            "jobId,benchmarkName,benchmarkType,mode,score,scoreError,scoreUnit,createdAt,imageVersion,instanceType,tags,params");
        for (ResultRow r : rows) {
            // Locale.ROOT for the same reason as printJson — a comma decimal separator turns one
            // CSV column into two.
            out.printf("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                r.jobId(), r.benchmarkName(), r.benchmarkType(), r.mode(),
                csvNumber(r.score()), csvNumber(r.scoreError()), r.scoreUnit(), r.createdAt(),
                r.imageVersion() != null ? r.imageVersion() : "",
                r.instanceType() != null ? r.instanceType() : "",
                csvField(csvTags(r.tags())), csvField(csvTags(r.params())));
        }
    }

    /** Every tag (or param), key-sorted, as {@code k=v;k=v}. */
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

    /** Key-sorted, so the same row always serialises identically. Tags and params alike. */
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

    /** An unknown value is an empty cell, CSV's form of JSON's {@code null}; never a number. */
    static String csvNumber(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.6f", value) : "";
    }

    /**
     * JSON has no NaN or Infinity literal, and a single-iteration JMH run reports {@code NaN}
     * score error routinely — emitting it produces a document {@code jq} refuses outright.
     */
    private static String jsonNumber(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.6f", value) : "null";
    }
}
