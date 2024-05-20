package pl.symentis.process;

import pl.symentis.services.options.JmhOptions;

import static pl.symentis.process.BenchmarkProcessBuilder.benchmarkProcessBuilder;

public class JmhBenchmarkProcessBuilderFactory {
    public static BenchmarkProcessBuilder prepopulatedJmhBenchmarkProcessBuilder(JmhOptions options){
        return benchmarkProcessBuilder(options.benchmarkOptions().benchmarkPath())
            .addArgumentWithValue("-rf", "json")
            .addArgumentIfValueIsNotNull("-wi",             options.warmupOptions().warmupIterations())
            .addArgumentIfValueIsNotNull("-wbs",            options.warmupOptions().warmupBatchSize())
            .addArgumentIfValueIsNotNull("-w",              options.warmupOptions().minimumWarmupIterationTime())
            .addArgumentIfValueIsNotNull("-wf",             options.warmupOptions().warmupForks())
            .addArgumentIfValueIsNotNull("-wm",             options.warmupOptions().warmupMode())
            .addArgumentIfValueIsNotNull("-wmb",            options.warmupOptions().warmupBenchmarks())
            .addArgumentIfValueIsNotNull("-i",              options.iterationOptions().iterations())
            .addArgumentIfValueIsNotNull("-r",              options.iterationOptions().minimumIterationTime())
            .addArgumentIfValueIsNotNull("-to",             options.iterationOptions().iterationTimeout())
            .addArgumentIfValueIsNotNull("-si",             options.iterationOptions().synchronizeIterations())
            .addArgumentIfValueIsNotNull("-gc",             options.iterationOptions().forceGCBetweenIterations())
            .addArgumentIfValueIsNotNull("-bs",             options.benchmarkOptions().batchSize())
            .addArgumentIfValueIsNotNull("-t",              options.benchmarkOptions().threads())
            .addArgumentIfValueIsNotNull("-bm",             options.benchmarkOptions().benchmarkMode())
            .addArgumentIfValueIsNotNull("-foe",            options.benchmarkOptions().failAfterUnrecoverableError())
            .addArgumentIfValueIsNotNull("-v",              options.benchmarkOptions().verbosityMode())
            .addArgumentIfValueIsNotNull("-f",              options.benchmarkOptions().forks())
            .addArgumentIfValueIsNotNull("-tg",             options.benchmarkOptions().threadGroups())
            .addArgumentIfValueIsNotNull("-tu",             options.benchmarkOptions().timeUnit())
            .addArgumentIfValueIsNotNull("-opi",            options.iterationOptions().operationsPerInvocations())
            .addArgumentIfValueIsNotNull("-e",              options.benchmarkOptions().excludeBenchmarkRegex())
            .addArgumentIfValueIsNotNull("-jvm",            options.jvmOptions().jvm())
            .addArgumentIfValueIsNotNull("-jvmArgs",        options.jvmOptions().jvmArgs())
            .addArgumentIfValueIsNotNull("-jvmArgsAppend",  options.jvmOptions().jvmArgsAppend())
            .addArgumentIfValueIsNotNull("-jvmArgsPrepend", options.jvmOptions().jvmArgsPrepend())
            .addArgumentIfValueIsNotNull("-rff",            options.outputOptions().machineReadableOutput())
            .withOutputPath(options.outputOptions().processOutput())
            .addOptionalArgument(options.benchmarkOptions().testNameRegex());
    }
}
