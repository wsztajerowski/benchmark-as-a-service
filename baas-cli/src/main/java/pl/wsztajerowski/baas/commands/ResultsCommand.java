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
import pl.wsztajerowski.baas.infra.SsmService;
import pl.wsztajerowski.baas.results.ResultRow;
import pl.wsztajerowski.baas.results.ResultsQueryService;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

@Command(
    name = "results",
    mixinStandardHelpOptions = true,
    description = "Query benchmark results from MongoDB."
)
public class ResultsCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(ResultsCommand.class);

    @Mixin LoggingMixin loggingMixin;

    @Option(names = "--request-id", description = "Filter by request ID.")
    String requestId;

    @Option(names = "--benchmark-name", description = "Filter by benchmark name (regex).")
    String benchmarkName;

    @Option(names = "--living-branches", description = "Filter by branches present in current git repo.")
    boolean livingBranches;

    @Option(names = "--all", description = "Return all results (no filter).")
    boolean all;

    @Option(names = "--format", description = "Output format: table (default), json, csv.", defaultValue = "table")
    String format;

    private final ConfigService configService = new ConfigService();

    @Override
    public Integer call() {
        BaasConfig config = configService.load();
        RunCommand.operatorCredentialsWarning(config).ifPresent(logger::warn);
        var factory = new AwsClientFactory(
            config.getAws().getRegion(), config.getAws().resolveOperatorProfile());

        String mongoUri;
        try (var ssm = factory.ssm()) {
            var opt = new SsmService(ssm).getParameterOptional("/" + config.getPrefix() + "/mongo/connection-string");
            if (opt.isEmpty()) {
                logger.error("No MongoDB URI found in SSM. Run: baas config set --mongo-uri <uri>");
                return 1;
            }
            mongoUri = opt.get();
        }

        try (var results = new ResultsQueryService(mongoUri)) {
            List<ResultRow> rows;
            if (requestId != null) {
                rows = results.queryByRequestId(requestId);
            } else if (benchmarkName != null) {
                rows = results.queryByBenchmarkName(benchmarkName);
            } else if (livingBranches) {
                rows = queryLivingBranches(results);
            } else {
                rows = results.queryAll();
            }

            switch (format.toLowerCase()) {
                case "json" -> printJson(rows);
                case "csv" -> printCsv(rows);
                default -> results.printTable(rows);
            }
        }
        return 0;
    }

    private List<ResultRow> queryLivingBranches(ResultsQueryService results) {
        List<String> branches = gitRemoteBranches();
        if (branches.isEmpty()) return results.queryAll();
        return branches.stream()
            .flatMap(b -> results.queryByBranch(b).stream())
            .toList();
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
