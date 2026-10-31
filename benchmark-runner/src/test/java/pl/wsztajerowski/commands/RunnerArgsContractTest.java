package pl.wsztajerowski.commands;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import pl.wsztajerowski.baas.model.BenchmarkType;
import pl.wsztajerowski.baas.model.Jdks;
import pl.wsztajerowski.baas.model.RunnerArgs;
import pl.wsztajerowski.baas.model.TagKeys;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The runner half of the contract user-data renders: the argument vector {@code baas run}'s script
 * passes — every type's subcommand name and every option, spelled from {@code baas-model} — parses
 * through the real command tree. The CLI half ({@code UserDataScriptBuilderTest}) checks the script
 * spells them from the same constants; between them a renamed option fails a build, not a paid job.
 */
class RunnerArgsContractTest {

    private static final String JOB_ID = "20260820T174432812Z-a3f9c21b";

    /** The shape of user-data's invocation: fixed options, caller tags, observed tags, then PARAMS. */
    private static List<String> argv(BenchmarkType type, Path benchmarkJar) {
        List<String> argv = new ArrayList<>(List.of(type.commandName(),
            RunnerArgs.JOB_ID, JOB_ID,
            RunnerArgs.CREATED_AT, "2026-08-20T17:44:32.812Z",
            RunnerArgs.RESULT_PATH, "jobs/lynx-journal/" + JOB_ID,
            RunnerArgs.S3_BUCKET, "baas-123456789012",
            RunnerArgs.BENCHMARK_PATH, benchmarkJar.toString(),
            RunnerArgs.RESULTS_TABLE, "baas-123456789012-results",
            RunnerArgs.JDK, Jdks.DEFAULT_ID,
            RunnerArgs.TAG, TagKeys.PROJECT + "=lynx-journal",
            RunnerArgs.TAG, TagKeys.TYPE + "=" + type.commandName(),
            RunnerArgs.TAG, TagKeys.JDK_VERSION + "=caller",
            RunnerArgs.TAG, TagKeys.JDK_VERSION + "=25.0.4"));
        argv.add("--");
        argv.add("MyBenchmark");
        return argv;
    }

    /** The runner checks the benchmark JAR exists while parsing; on the instance it is /app/benchmark-under-test.jar. */
    @Test
    void everyTypesInvocationParsesThroughTheRunnersCommandTree(@TempDir Path dir) throws IOException {
        assertEveryTypeParses(Files.createFile(dir.resolve("benchmark-under-test.jar")));
    }

    private static void assertEveryTypeParses(Path jar) {
        for (BenchmarkType type : BenchmarkType.values()) {
            var parsed = new CommandLine(new TestWrapper()).parseArgs(argv(type, jar).toArray(String[]::new));

            assertThat(parsed.subcommand().commandSpec().name()).isEqualTo(type.commandName());
            var options = (ApiCommonSharedOptions) parsed.subcommand().commandSpec()
                .mixins().get("apiCommonSharedOptions").userObject();
            var common = options.getRequestOptions();
            assertThat(common.jobId()).isEqualTo(JOB_ID);
            assertThat(common.createdAt()).isEqualTo(Instant.parse("2026-08-20T17:44:32.812Z"));
            assertThat(common.resultPath()).isEqualTo(Path.of("jobs/lynx-journal/" + JOB_ID));
            assertThat(common.project()).isEqualTo("lynx-journal");
            // The observed value is rendered after the caller's, and the runner's tag map is last-wins.
            assertThat(common.tags()).containsEntry(TagKeys.JDK_VERSION, "25.0.4");
            assertThat(options.jdk).isEqualTo(Jdks.DEFAULT_ID);
            assertThat(options.resultsTableName).isEqualTo("baas-123456789012-results");
        }
    }

    @Test
    void theRunnerHasExactlyTheTypesTheCliAccepts() {
        var names = new CommandLine(new TestWrapper()).getSubcommands().keySet();

        assertThat(names).containsAll(BenchmarkType.commandNames());
        assertThat(names.stream().filter(n -> !"help".equals(n)).toList())
            .containsExactlyInAnyOrderElementsOf(BenchmarkType.commandNames());
    }
}
