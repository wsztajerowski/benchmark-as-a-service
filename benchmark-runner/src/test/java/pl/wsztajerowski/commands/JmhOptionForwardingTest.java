package pl.wsztajerowski.commands;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import pl.wsztajerowski.baas.model.RunnerArgs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static pl.wsztajerowski.process.JmhBenchmarkProcessBuilderFactory.prepopulatedJmhBenchmarkProcessBuilder;

/**
 * What a JMH flag passed after {@code baas run jmh --} becomes on the JMH command line the runner
 * starts. A dropped or renamed mapping used to pass every unit test and show up only in a paid
 * job's numbers (review T3). {@link RunnerArgsContractTest} covers the options the CLI composes;
 * this covers the caller's.
 */
class JmhOptionForwardingTest {

    private static List<String> jmhCommandLine(Path jar, String... jmhArgs) {
        List<String> argv = new ArrayList<>(List.of("jmh", RunnerArgs.BENCHMARK_PATH, jar.toString(),
            RunnerArgs.RESULTS_TABLE, "t", RunnerArgs.S3_BUCKET, "b"));
        argv.addAll(List.of(jmhArgs));
        var parsed = new CommandLine(new TestWrapper()).parseArgs(argv.toArray(String[]::new));
        var options = (ApiJmhOptions) parsed.subcommand().commandSpec().mixins().get("apiJmhOptions").userObject();
        return prepopulatedJmhBenchmarkProcessBuilder(options.getJmhOptions()).commands();
    }

    /** {@code -f 1} is the pair {@code -f}, {@code 1}, adjacent, wherever it sits. */
    private static void assertPair(List<String> commandLine, String flag, String value) {
        int at = commandLine.indexOf(flag);
        assertThat(at).as(flag + " reaches JMH").isNotNegative();
        assertThat(commandLine.get(at + 1)).as(flag + "'s value").isEqualTo(value);
    }

    @Test
    void theCommonJmhFlagsReachTheJmhCommandLineUnchanged(@TempDir Path dir) throws IOException {
        Path jar = Files.createFile(dir.resolve("benchmarks.jar"));

        List<String> jmh = jmhCommandLine(jar,
            "-f", "1", "-wi", "2", "-i", "3", "-bm", "avgt", "-t", "4", "-tu", "ms", "-e", "Excluded",
            "-r", "5s", "-w", "6s", "-to", "7m", "-bs", "8", "-wf", "1", "-opi", "10",
            "-jvmArgs", "-Xmx1g", "-jvmArgsAppend", "-Dappended", "-jvmArgsPrepend", "-Dprepended",
            "MyBenchmark");

        assertThat(jmh.subList(0, 3)).containsExactly("java", "-jar", jar.toString());
        assertPair(jmh, "-f", "1");
        assertPair(jmh, "-wi", "2");
        assertPair(jmh, "-i", "3");
        assertPair(jmh, "-bm", "avgt");
        assertPair(jmh, "-t", "4");
        assertPair(jmh, "-tu", "ms");
        assertPair(jmh, "-e", "Excluded");
        assertPair(jmh, "-r", "5s");
        assertPair(jmh, "-w", "6s");
        assertPair(jmh, "-to", "7m");
        assertPair(jmh, "-bs", "8");
        assertPair(jmh, "-wf", "1");
        assertPair(jmh, "-opi", "10");
        assertPair(jmh, "-jvmArgs", "-Xmx1g");
        assertPair(jmh, "-jvmArgsAppend", "-Dappended");
        assertPair(jmh, "-jvmArgsPrepend", "-Dprepended");
        assertThat(jmh.getLast()).as("the include regex comes last").isEqualTo("MyBenchmark");
    }

    /** The runner's {@code -bvm} is JMH's {@code -v}: the one flag whose name changes on the way. */
    @Test
    void theVerbosityFlagIsRenamedToJmhsOwn(@TempDir Path dir) throws IOException {
        Path jar = Files.createFile(dir.resolve("benchmarks.jar"));

        assertPair(jmhCommandLine(jar, "-bvm", "SILENT", "MyBenchmark"), "-v", "SILENT");
    }

    /** JMH's own defaults apply to anything not passed: nothing is forwarded for it, not even a null. */
    @Test
    void anOmittedFlagIsLeftToJmh(@TempDir Path dir) throws IOException {
        Path jar = Files.createFile(dir.resolve("benchmarks.jar"));

        List<String> jmh = jmhCommandLine(jar, "MyBenchmark");

        assertThat(jmh).doesNotContain("-f", "-wi", "-i", "-bm", "-t", "-jvmArgs", "null");
        assertPair(jmh, "-rf", "json");
    }
}
