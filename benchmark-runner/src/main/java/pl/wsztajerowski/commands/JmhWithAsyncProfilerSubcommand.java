package pl.wsztajerowski.commands;

import pl.wsztajerowski.infra.StorageService;
import pl.wsztajerowski.services.JmhWithAsyncProfilerSubcommandService;
import pl.wsztajerowski.baas.model.BenchmarkType;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static picocli.CommandLine.Model.CommandSpec;
import static picocli.CommandLine.ParameterException;
import static picocli.CommandLine.Spec;
import static pl.wsztajerowski.FileUtils.getWorkingDirectory;
import static pl.wsztajerowski.options.AsyncProfilerOptions.asyncProfilerOptionsBuilder;

@Command(name = BenchmarkType.JMH_WITH_ASYNC_NAME, description = "Run JHM benchmarks with Async profiler")
public class JmhWithAsyncProfilerSubcommand implements Runnable {
    @Spec
    CommandSpec spec;

    @Mixin
    LoggingMixin loggingMixin;

    @Mixin
    private ApiJmhOptions apiJmhOptions;

    @Mixin
    private ApiCommonSharedOptions apiCommonSharedOptions;

    private Path asyncPath;

    @Option(names = {"-ap", "--async-path"}, defaultValue = "${ASYNC_PATH:-/app/async-profiler/lib/libasyncProfiler.so}", description = "Path to Async profiler (default: ${DEFAULT-VALUE})")
    public void setAsyncPath(Path path) {
        if(!Files.exists(path)){
            throw new ParameterException(spec.commandLine(),
                "Invalid path to async profiler: %s".formatted(path));
        }
        this.asyncPath = path;
    }

    @Option(names = {"-ai", "--async-interval"}, description = "Profiling interval (default: ${DEFAULT-VALUE})")
    int asyncInterval = 9990;

    @Option(names = {"-ae", "--async-event"}, description = """
        Event to sample: cpu, alloc, lock, wall, itimer; com.foo.Bar.methodName;
        any event from `perf list` e.g. cache-misses. (default: ${DEFAULT-VALUE})
        See https://github.com/async-profiler/async-profiler/blob/master/docs/ProfilingModes.md for details."""
    )
    String asyncEvent = "cpu";

    @Option(names = {"-aot", "--async-output-type"}, description = "Output format(s). Supported: [text, collapsed, flamegraph, tree, jfr] (default: ${DEFAULT-VALUE})")
    String asyncOutputType = "flamegraph";

    @Option(names = {"-aop", "--async-output-path"}, description = "Profiler output path (default: ${DEFAULT-VALUE})")
    Path asyncOutputPath = getWorkingDirectory().resolve("async-output");

    @Option(names = {"-aap", "--async-additional-param"}, description = "Provide advance raw parameters as a map in format: KEY=VALUE")
    Map<String, String> asyncAdditionalParams;

    @Override
    public void run() {
        // In the order the builder used to evaluate them: the request options can refuse the run
        // (no project) before any client is built or any storage line is logged.
        var common = apiCommonSharedOptions.getRequestOptions();
        var jmh = apiJmhOptions.getJmhOptions();
        var async = asyncProfilerOptionsBuilder()
            .withAsyncPath(asyncPath)
            .withAsyncInterval(asyncInterval)
            .withAsyncEvent(asyncEvent)
            .withAsyncOutputType(asyncOutputType)
            .withAsyncOutputPath(asyncOutputPath)
            .withAsyncAdditionalOptions(asyncAdditionalParams)
            .build();
        var store = apiCommonSharedOptions.buildResultsStore();
        var storage = StorageService.of(apiCommonSharedOptions.getS3Options());
        new JmhWithAsyncProfilerSubcommandService(storage, store, common, jmh, async).executeCommand();
    }
}
