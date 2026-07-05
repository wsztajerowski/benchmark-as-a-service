package pl.wsztajerowski.baas.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.config.ConfigService;
import pl.wsztajerowski.baas.infra.AwsClientFactory;
import pl.wsztajerowski.baas.infra.SsmService;
import pl.wsztajerowski.baas.results.ResultRow;
import pl.wsztajerowski.baas.results.ResultsQueryService;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
    name = "results",
    mixinStandardHelpOptions = true,
    description = "Query benchmark results from MongoDB."
)
public class ResultsCommand implements Callable<Integer> {

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
        var factory = new AwsClientFactory(config.getAws().getRegion(), config.getAws().getProfile());

        String mongoUri;
        try (var ssm = factory.ssm()) {
            var opt = new SsmService(ssm).getParameterOptional("/" + config.getPrefix() + "/mongo/connection-string");
            if (opt.isEmpty()) {
                System.err.println("No MongoDB URI found in SSM. Run: baas config set --mongo-uri <uri>");
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

    private void printJson(List<ResultRow> rows) {
        System.out.println("[");
        for (int i = 0; i < rows.size(); i++) {
            ResultRow r = rows.get(i);
            System.out.printf(
                "  {\"requestId\":\"%s\",\"benchmarkName\":\"%s\",\"benchmarkType\":\"%s\"," +
                "\"mode\":\"%s\",\"score\":%.6f,\"scoreError\":%.6f,\"scoreUnit\":\"%s\",\"createdAt\":\"%s\"}%s%n",
                r.requestId(), r.benchmarkName(), r.benchmarkType(), r.mode(),
                r.score(), r.scoreError(), r.scoreUnit(), r.createdAt(),
                i < rows.size() - 1 ? "," : "");
        }
        System.out.println("]");
    }

    private void printCsv(List<ResultRow> rows) {
        System.out.println("requestId,benchmarkName,benchmarkType,mode,score,scoreError,scoreUnit,createdAt");
        for (ResultRow r : rows) {
            System.out.printf("%s,%s,%s,%s,%.6f,%.6f,%s,%s%n",
                r.requestId(), r.benchmarkName(), r.benchmarkType(), r.mode(),
                r.score(), r.scoreError(), r.scoreUnit(), r.createdAt());
        }
    }
}
