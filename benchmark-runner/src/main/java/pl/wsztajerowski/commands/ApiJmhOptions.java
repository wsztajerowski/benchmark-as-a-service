package pl.wsztajerowski.commands;

import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import pl.wsztajerowski.services.options.*;

import java.nio.file.Files;
import java.nio.file.Path;

import static picocli.CommandLine.Model.CommandSpec;
import static picocli.CommandLine.ParameterException;
import static picocli.CommandLine.Spec;

@Command
public class ApiJmhOptions {

    @Spec
    static CommandSpec spec;

    Path benchmarkPath;

    @Option(names = "--benchmark-path", defaultValue = "jmh-benchmarks.jar", description = "Path to benchmark jar (default: ${DEFAULT-VALUE})")
    public void setBenchmarkPath(Path path) {
        if(!Files.exists(path)){
            throw new ParameterException(spec.commandLine(),
                "Invalid benchmark path: %s".formatted(path));
        }
        this.benchmarkPath = path;
    }

    @Option(names = {"-rff", "--machine-readable-output"}, defaultValue = "jmh-results.json", description = "Write machine-readable results to a given json file. (default: ${DEFAULT-VALUE})")
    Path machineReadableOutput;

    @Option(names = {"-o", "--process-output"}, defaultValue = "jmh-output.txt", description = "Write benchmark process output to a given file. (default: ${DEFAULT-VALUE})")
    Path processOutput;

    @ArgGroup(validate = false, heading = "Benchmark options%n")
    BenchmarkSection benchmarkSection = new BenchmarkSection();

    @ArgGroup(validate = false, heading = "Warmup options%n")
    WarmupSection warmupSection = new WarmupSection();

    @ArgGroup(validate = false, heading = "Iteration options%n")
    IterationSection iterationSection = new IterationSection();

    @ArgGroup(validate = false, heading = "JVM options%n")
    JvmSection jvmSection = new JvmSection();

    static class BenchmarkSection {

        @Option(names = {"-bs", "--batch-size"}, description = "Batch size: number of benchmark method calls per operation. Some benchmark modes may ignore this setting, please check this separately. (default: 1)")
        Integer batchSize;

        @Option(names = {"-t", "--threads"}, description = "Number of worker threads to run with. 'max' means the maximum number of hardware threads available on the machine, figured out by JMH itself. (default: 1)")
        Integer threads;

        @Option(names = {"-bm", "--benchmark-mode"}, description = "Benchmark mode. Available modes are: [Throughput/thrpt, AverageTime/avgt, SampleTime/sample, SingleShotTime/ss, All/all]. (default: Throughput)")
        String benchmarkMode;

        @Option(names = {"-foe", "--fail-fast"}, negatable = true, description = "Should JMH fail immediately if any benchmark had experienced an unrecoverable error? This helps to make quick sanity tests for benchmark suites, as well as make the automated runs with checking error codes. (default: false)")
        Boolean failAfterUnrecoverableError;

        /* CHANGE FROM JMH OPTION NAME - "bvm" instead of "v" */
        @Option(names = {"-bvm", "--benchmark-verbosity-mode"}, description = "Verbosity mode. Available modes are: [SILENT, NORMAL, EXTRA]. (default: NORMAL)")
        String verbosityMode;

        @Option(names = {"-f", "--forks"}, description = "How many times to fork a single benchmark. Use 0 to disable forking altogether. Warning: disabling forking may have detrimental impact on benchmark and infrastructure reliability, you might want to use different warmup mode instead. (default: 5)")
        Integer forks;

        @Option(names = {"-tg", "--thread-groups"}, description = "Override thread group distribution for asymmetric benchmarks. This option expects a comma-separated list of thread counts within the group. See @Group/@GroupThreads Javadoc for more information.")
        String threadGroups;

        @Option(names = {"-tu", "--time-unit"}, description = "Override time unit in benchmark results. Available time units are: [m, s, ms, us, ns]. (default: SECONDS)")
        String timeUnit;

        @Option(names = {"-e", "--exclude-benchmark-regex"}, description = "Benchmarks to exclude from the run.")
        String excludeBenchmarkRegex;

        @Parameters(index = "0", description = "Test name regex", arity = "0..1")
        String testNameRegex;
    }

    static class WarmupSection {
        @Option(names = {"-wi", "--warmup-iterations"}, description = "Number of warmup iterations to do. Warmup iterations are not counted towards the benchmark score. (default: 0 for SingleShotTime, and 5 for all other modes)")
        Integer warmupIterations;

        @Option(names = {"-wbs", "--warmup-batch-size"}, description = "Warmup batch size: number of benchmark method callsper operation. Some benchmark modes may ignore this setting. (default: 1)")
        Integer warmupBatchSize;

        @Option(names = {"-w", "--minimum-warmup-iteration-time"}, description = "Minimum time to spend at each warmup iteration. Benchmarks may generally run longer than iteration duration. (default: 10 s)")
        String minimumWarmupIterationTime;

        @Option(names = {"-wf", "--warmup-forks"}, description = "How many warmup forks to make for a single benchmark. All iterations within the warmup fork are not counted towards the benchmark score. Use 0 to disable warmup forks. (default: 0)")
        Integer warmupForks;

        @Option(names = {"-wm", "--warmup-mode"}, description = "Warmup mode for warming up selected benchmarks. Warmup modes are: INDI = Warmup each benchmark individually, then measure it. BULK = Warmup all benchmarks first, then do all the measurements. BULK_INDI = Warmup all benchmarks first, then re-warmup each benchmark individually, then measure it. (default: INDI)")
        String warmupMode;

        @Option(names = {"-wmb", "--warmup-benchmarks"}, description = "Warmup benchmarks to include in the run in addition to already selected by the primary filters. Harness will not measure these benchmarks, but only use them for the warmup.")
        String warmupBenchmarks;
    }

    static class IterationSection {
        @Option(names = {"-i", "--iterations"}, description = "Number of measurement iterations to do. Measurement iterations are counted towards the benchmark score. (default: 1 for SingleShotTime, and 5 for all other modes)")
        Integer iterations;

        @Option(names = {"-r", "--minimum-iteration-time"}, description = "Minimum time to spend at each measurement iteration. Benchmarks may generally run longer than iteration duration. (default: 10 s)")
        String minimumIterationTime;

        @Option(names = {"-to", "--iteration-timeout"}, description = "Timeout for benchmark iteration. After reaching this timeout, JMH will try to interrupt the running tasks. Non-cooperating benchmarks may ignore this timeout. (default: 10 min)")
        String iterationTimeout;

        @Option(names = {"-opi", "--operations-per-invocation"}, description = "Override operations per invocation, see JMH @OperationsPerInvocation Javadoc for details. (default: 1)")
        Integer operationsPerInvocations;

        @Option(names = {"-si", "--synchronize-iterations"}, negatable = true, description = "Should JMH synchronize iterations? This would significantly lower the noise in multithreaded tests, by making sure the measured part happens only when all workers are running. (default: true)")
        Boolean synchronizeIterations;

        @Option(names = {"-gc", "--force-gc-between-iterations"}, negatable = true, description = "Should JMH force GC between iterations? Forcing the GC may help to lower the noise in GC-heavy benchmarks, at the expense of jeopardizing GC ergonomics decisions. Use with care. (default: false)")
        Boolean forceGCBetweenIterations;
    }

    static class JvmSection {
        @Option(names = "-jvm", description = "Use given JVM for runs. This option only affects forked runs.")
        String jvm;

        @Option(names = "-jvmArgs", description = "Use given JVM arguments. Most options are inherited from the host VM options, but in some cases you want to pass the options only to a forked VM. Either single space-separated option line, or multiple options are accepted. This option only affects forked runs.")
        String jvmArgs;

        @Option(names = "-jvmArgsAppend", description = "Same as jvmArgs, but append these options after the already given JVM args.")
        String jvmArgsAppend;

        @Option(names = "-jvmArgsPrepend", description = "Same as jvmArgs, but prepend these options before the already given JVM arg.")
        String jvmArgsPrepend;
    }

    public JmhJvmOptions getJmhJvmOptions() {
        return JmhJvmOptions.jmhJvmOptionsBuilder()
            .withJvm(jvmSection.jvm)
            .withJvmArgs(jvmSection.jvmArgs)
            .withJvmArgsAppend(jvmSection.jvmArgsAppend)
            .withJvmArgsPrepend(jvmSection.jvmArgsPrepend)
            .build();
    }

    public JmhIterationOptions getJmhIterationOptions() {
        return JmhIterationOptions.jmhIterationOptionsBuilder()
            .withForceGCBetweenIterations(iterationSection.forceGCBetweenIterations)
            .withIterations(iterationSection.iterations)
            .withIterationTimeout(iterationSection.iterationTimeout)
            .withMinimumIterationTime(iterationSection.minimumIterationTime)
            .withOperationsPerInvocations(iterationSection.operationsPerInvocations)
            .withSynchronizeIterations(iterationSection.synchronizeIterations)
            .build();
    }

    public JmhOutputOptions getJmhOutputOptions() {
        return JmhOutputOptions.jmhOutputOptionsBuilder()
            .withMachineReadableOutput(machineReadableOutput)
            .withProcessOutput(processOutput)
            .build();
    }

    public JmhWarmupOptions getJmhWarmupOptions() {
        return JmhWarmupOptions.jmhWarmupOptionsBuilder()
            .withWarmupBatchSize(warmupSection.warmupBatchSize)
            .withWarmupBenchmarks(warmupSection.warmupBenchmarks)
            .withWarmupForks(warmupSection.warmupForks)
            .withWarmupIterations(warmupSection.warmupIterations)
            .withWarmupMode(warmupSection.warmupMode)
            .withMinimumWarmupIterationTime(warmupSection.minimumWarmupIterationTime)
            .build();
    }

    public JmhBenchmarkOptions getJmhBenchmarkOptions() {
        return JmhBenchmarkOptions.jmhBenchmarkOptionsBuilder()
            .withBatchSize(benchmarkSection.batchSize)
            .withBenchmarkMode(benchmarkSection.benchmarkMode)
            .withBenchmarkPath(benchmarkPath)
            .withExcludeBenchmarkRegex(benchmarkSection.excludeBenchmarkRegex)
            .withForks(benchmarkSection.forks)
            .withFailAfterUnrecoverableError(benchmarkSection.failAfterUnrecoverableError)
            .withThreadGroups(benchmarkSection.threadGroups)
            .withThreads(benchmarkSection.threads)
            .withTimeUnit(benchmarkSection.timeUnit)
            .withVerbosityMode(benchmarkSection.verbosityMode)
            .withTestNameRegex(benchmarkSection.testNameRegex)
            .build();
    }

    public JmhOptions getJmhOptions(){
        return new JmhOptions(
            getJmhBenchmarkOptions(),
            getJmhOutputOptions(),
            getJmhWarmupOptions(),
            getJmhIterationOptions(),
            getJmhJvmOptions()
        );
    }
}
