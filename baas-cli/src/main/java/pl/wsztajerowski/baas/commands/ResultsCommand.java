package pl.wsztajerowski.baas.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import pl.wsztajerowski.baas.LoggingMixin;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.config.ConfigService;
import pl.wsztajerowski.baas.infra.AwsClientFactory;
import pl.wsztajerowski.baas.results.ResultRow;
import pl.wsztajerowski.baas.results.ResultsFilters;
import pl.wsztajerowski.baas.results.ResultsGrouping;
import pl.wsztajerowski.baas.results.ResultsQueryService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
    name = "results",
    mixinStandardHelpOptions = true,
    description = "Query benchmark results from the results table."
)
public class ResultsCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(ResultsCommand.class);

    @Mixin LoggingMixin loggingMixin;

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

    private final ConfigService configService = new ConfigService();

    @Override
    public Integer call() {
        BaasConfig config = configService.load();
        RunCommand.operatorCredentialsWarning(config).ifPresent(logger::warn);

        String conflict = requestIdConflict();
        if (conflict != null) {
            logger.error("--request-id names one run, so it cannot be combined with {}.", conflict);
            return 2;
        }

        String tableName = config.getAws().getResultsTable();
        if (tableName == null || tableName.isBlank()) {
            logger.error("No results table in config. Run: baas config sync --core-stack-name <stack>");
            return 1;
        }

        var factory = new AwsClientFactory(
            config.getAws().getRegion(), config.getAws().resolveOperatorProfile());

        try (var results = new ResultsQueryService(factory.dynamoDb(), tableName)) {
            List<ResultRow> rows = requestId != null
                ? results.queryByRequestId(requestId)
                : results.queryProject(resolveProject());

            if (requestId == null) {
                rows = ResultsFilters.byBenchmarkName(rows, benchmarkName);
                rows = ResultsFilters.byTags(rows, tags);
                if (livingBranches) {
                    rows = ResultsFilters.byLivingBranches(rows, gitRemoteBranches());
                }
                ResultsFilters.unknownTagWarning(rows, tags).ifPresent(logger::warn);
                if (!all) {
                    rows = ResultsGrouping.bestPerGroup(rows, groupBy);
                }
            }

            rows = ResultsGrouping.sortedForDisplay(rows);
            if (limit != null && limit >= 0 && rows.size() > limit) {
                logger.info("Reporting {} of {} rows (--limit).", limit, rows.size());
                rows = rows.subList(0, limit);
            }

            switch (format.toLowerCase(Locale.ROOT)) {
                case "json" -> printJson(rows);
                case "csv" -> printCsv(rows);
                default -> results.printTable(rows);
            }
        }
        return 0;
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
     * Result payloads stay on stdout, never the logger: {@code baas results --format json | jq}
     * has to see clean JSON, and SimpleLogger writes to stderr with a timestamp on every line.
     * Same reasoning covers {@link #printCsv} and {@link ResultsQueryService#printTable}.
     */
    private void printJson(List<ResultRow> rows) {
        System.out.println("[");
        for (int i = 0; i < rows.size(); i++) {
            ResultRow r = rows.get(i);
            // imageVersion/instanceType ride along so a machine consumer can tell comparable rows
            // from incomparable ones — the table says so in prose, and `| jq` cannot read prose.
            // Null for every run recorded before the prebaked-image change.
            System.out.printf(Locale.ROOT,
                "  {\"requestId\":\"%s\",\"benchmarkName\":\"%s\",\"benchmarkType\":\"%s\"," +
                "\"mode\":\"%s\",\"score\":%s,\"scoreError\":%s,\"scoreUnit\":\"%s\"," +
                "\"createdAt\":\"%s\",\"imageVersion\":%s,\"instanceType\":%s}%s%n",
                r.requestId(), r.benchmarkName(), r.benchmarkType(), r.mode(),
                jsonNumber(r.score()), jsonNumber(r.scoreError()), r.scoreUnit(), r.createdAt(),
                jsonOrNull(r.imageVersion()), jsonOrNull(r.instanceType()),
                i < rows.size() - 1 ? "," : "");
        }
        System.out.println("]");
    }

    private void printCsv(List<ResultRow> rows) {
        System.out.println(
            "requestId,benchmarkName,benchmarkType,mode,score,scoreError,scoreUnit,createdAt,imageVersion,instanceType");
        for (ResultRow r : rows) {
            // Locale.ROOT for the same reason as printJson — a comma decimal separator turns one
            // CSV column into two.
            System.out.printf(Locale.ROOT, "%s,%s,%s,%s,%.6f,%.6f,%s,%s,%s,%s%n",
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
