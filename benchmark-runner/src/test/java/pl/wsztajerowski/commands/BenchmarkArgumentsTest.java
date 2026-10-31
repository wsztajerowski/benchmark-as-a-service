package pl.wsztajerowski.commands;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import pl.wsztajerowski.baas.model.RunnerArgs;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The runner takes its own options, then {@code --}, then the benchmark's arguments, which it keeps
 * exactly as given — and refuses the ones BaaS manages or that would measure nothing, before any
 * client is built.
 */
class BenchmarkArgumentsTest {

    private static List<String> argv(String type, Path jar, String... benchmarkArgs) {
        List<String> argv = new ArrayList<>(List.of(type, RunnerArgs.BENCHMARK_PATH, jar.toString(),
            RunnerArgs.PROJECT, "p", RunnerArgs.RESULTS_TABLE, "t", RunnerArgs.S3_BUCKET, "b", "--"));
        argv.addAll(List.of(benchmarkArgs));
        return argv;
    }

    @Test
    void everyArgumentAfterTheSeparatorIsKeptAsGiven(@TempDir Path dir) throws IOException {
        Path jar = Files.createFile(dir.resolve("benchmarks.jar"));
        String[] given = {"MyBenchmark", "-p", "size=10,100", "-v", "EXTRA", "-jvmArgs", "-Xmx2g -XX:+UseZGC",
            "-prof", "async:event=wall;threads=true", "--tag", "type=x"};

        var parsed = new CommandLine(new TestWrapper()).parseArgs(argv("jmh", jar, given).toArray(String[]::new));

        var jmh = (JmhSubcommand) parsed.subcommand().commandSpec().userObject();
        assertThat(jmh.benchmarkArgs).containsExactly(given);
    }

    @Test
    void jcstressArgumentsAreKeptAsGiven(@TempDir Path dir) throws IOException {
        Path jar = Files.createFile(dir.resolve("tests.jar"));

        var parsed = new CommandLine(new TestWrapper())
            .parseArgs(argv("jcstress", jar, "-m", "sanity", "-t", "Foo").toArray(String[]::new));

        var jcstress = (JCStressSubcommand) parsed.subcommand().commandSpec().userObject();
        assertThat(jcstress.benchmarkArgs).containsExactly("-m", "sanity", "-t", "Foo");
    }

    @Test
    void aManagedArgumentIsRefusedBeforeAnythingRuns(@TempDir Path dir) throws IOException {
        Path jar = Files.createFile(dir.resolve("benchmarks.jar"));

        assertThat(execute(argv("jmh", jar, "MyBenchmark", "-rff", "/tmp/x.json")))
            .returns(2, Run::exitCode)
            .extracting(Run::err).asString().contains("-rff is managed by BaaS");
        assertThat(execute(argv("jmh", jar, "MyBenchmark", "-pro", "async:dir=/tmp/x")).err())
            .contains("dir in -prof async is managed by BaaS");
        assertThat(execute(argv("jcstress", jar, "-r", "/tmp/report")).err())
            .contains("-r is managed by BaaS");
    }

    @Test
    void aNoRunArgumentIsRefusedBeforeAnythingRuns(@TempDir Path dir) throws IOException {
        Path jar = Files.createFile(dir.resolve("benchmarks.jar"));

        assertThat(execute(argv("jmh", jar, "-lp")))
            .returns(2, Run::exitCode)
            .extracting(Run::err).asString().contains("-lp makes JMH print and exit without measuring");
        assertThat(execute(argv("jcstress", jar, "-p", "old.bin.gz")).err())
            .contains("-p makes JCStress print and exit without measuring");
    }

    /** JMH would list and exit, ignoring the rest: the job would not do what its command line says. */
    @Test
    void aProfilerListingBesideOtherArgumentsIsRefused(@TempDir Path dir) throws IOException {
        Path jar = Files.createFile(dir.resolve("benchmarks.jar"));

        assertThat(execute(argv("jmh", jar, "-lprof", "-f", "1")))
            .returns(2, Run::exitCode)
            .extracting(Run::err).asString().contains("-lprof lists JMH's profilers and exits")
            .contains("must be the only argument after --");
        assertThat(execute(argv("jmh", jar, "MyBenchmark", "-lpr")).exitCode()).isEqualTo(2);
    }

    /** Without the separator a JMH flag is a runner option the runner does not have. */
    @Test
    void aBenchmarkFlagBeforeTheSeparatorIsUnknown(@TempDir Path dir) throws IOException {
        Path jar = Files.createFile(dir.resolve("benchmarks.jar"));
        List<String> argv = new ArrayList<>(argv("jmh", jar));
        argv.remove("--");
        argv.addAll(List.of("-f", "1"));

        assertThat(execute(argv).exitCode()).isEqualTo(2);
    }

    private record Run(int exitCode, String err) {
    }

    private static Run execute(List<String> argv) {
        StringWriter err = new StringWriter();
        int exitCode = new CommandLine(new TestWrapper())
            .setErr(new PrintWriter(err))
            .execute(argv.toArray(String[]::new));
        return new Run(exitCode, err.toString());
    }
}
