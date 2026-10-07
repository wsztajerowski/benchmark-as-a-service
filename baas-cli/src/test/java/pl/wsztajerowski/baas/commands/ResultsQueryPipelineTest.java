package pl.wsztajerowski.baas.commands;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import pl.wsztajerowski.baas.BaasApp;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.results.ResultRow;
import pl.wsztajerowski.baas.results.ResultsQueryService;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared pipeline on {@code results query}: filter, [--best-per], sort, --offset, --limit —
 * every measurement by default, newest first, twenty at a time.
 */
class ResultsQueryPipelineTest {

    @TempDir Path dir;

    /** Thirty rows, one per day of September, alternating between two branches. */
    private static List<ResultRow> thirtyDays() {
        List<ResultRow> rows = new ArrayList<>();
        for (int day = 1; day <= 30; day++) {
            String created = "2026-09-%02dT00:00:00.000Z".formatted(day);
            rows.add(new ResultRow("job-" + day, "com.example.Bench.run", "jmh", "thrpt", day, 0.0, "ops/s",
                created, Map.of("branch", day % 2 == 0 ? "main" : "feature", "project", "p"), "p"));
        }
        return rows;
    }

    private record Run(int exit, String out, String err) {}

    private Run query(String... args) {
        var stored = new BaasConfig();
        stored.setPrefix("baas-123456789012");
        pl.wsztajerowski.baas.TestDeployments.save(dir, stored);

        var out = new StringWriter();
        CommandLine.IFactory defaults = CommandLine.defaultFactory();
        CommandLine.IFactory factory = new CommandLine.IFactory() {
            @Override
            @SuppressWarnings("unchecked")
            public <K> K create(Class<K> cls) throws Exception {
                if (cls != ResultsQuerySubcommand.class) {
                    return defaults.create(cls);
                }
                var command = new ResultsQuerySubcommand() {
                    @Override
                    ResultsQueryService openResults(BaasConfig c, String table) {
                        return new ResultsQueryService(null, table) {
                            @Override
                            public List<ResultRow> queryProject(String p, boolean all) {
                                return thirtyDays();
                            }

                            @Override
                            public void close() {
                            }
                        };
                    }
                };
                command.console = Console.plain(new PrintWriter(out, true));
                return (K) command;
            }
        };
        var err = new StringWriter();
        List<String> line = new ArrayList<>(List.of("query", "--project", "p",
            "--format", "json"));
        line.addAll(List.of(args));
        int exit = new CommandLine(new BaasApp(dir), factory).setErr(new PrintWriter(err, true))
            .execute(line.toArray(String[]::new));
        return new Run(exit, out.toString(), err.toString());
    }

    private static List<String> jobIds(String json) {
        var matcher = Pattern.compile("\"jobId\":\"(job-\\d+)\"").matcher(json);
        List<String> ids = new ArrayList<>();
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }

    @Test
    void theDefaultIsTheTwentyNewestEveryMeasurement() {
        var run = query();

        assertThat(run.exit()).as(run.err()).isZero();
        List<String> ids = jobIds(run.out());
        assertThat(ids).hasSize(20).startsWith("job-30", "job-29").endsWith("job-11");
    }

    @Test
    void anOffsetPagesBackwardsInTime() {
        List<String> ids = jobIds(query("--offset", "20").out());

        assertThat(ids).hasSize(10).startsWith("job-10").endsWith("job-1");
    }

    @Test
    void limitZeroMeansNoLimit() {
        assertThat(jobIds(query("--limit", "0").out())).hasSize(30);
    }

    @Test
    void bestPerKeepsOneRowPerBranch() {
        List<String> ids = jobIds(query("--best-per", "branch").out());

        assertThat(ids).containsExactlyInAnyOrder("job-30", "job-29");
    }

    @Test
    void anExcludedTagIsDroppedBeforePaging() {
        List<String> ids = jobIds(query("--exclude-tag", "branch=feature", "--limit", "0").out());

        assertThat(ids).hasSize(15).allMatch(id -> Integer.parseInt(id.substring(4)) % 2 == 0);
    }

    @Test
    void anotherOrderIsChosenExplicitly() {
        List<String> ids = jobIds(query("--sort-by", "score", "--asc", "--limit", "3").out());

        assertThat(ids).containsExactly("job-1", "job-2", "job-3");
    }
}
