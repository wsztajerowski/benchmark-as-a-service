package pl.wsztajerowski.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import pl.wsztajerowski.baas.model.BenchmarkType;
import pl.wsztajerowski.baas.model.RunnerArgs;
import pl.wsztajerowski.infra.StorageService;
import pl.wsztajerowski.options.JmhRun;
import pl.wsztajerowski.services.JmhSubcommandService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static pl.wsztajerowski.FileUtils.getWorkingDirectory;

@Command(name = BenchmarkType.JMH_NAME,
    description = "Run JMH benchmarks. Everything after -- reaches the benchmark JAR as given, "
        + "except -rf, -rff and -o, and the dir (and async's libPath) of an async or jfr profiler, "
        + "which the runner sets. -foe defaults to true: pass -foe false to keep the benchmarks that "
        + "succeeded when another fails.")
public class JmhSubcommand implements Runnable {
    private static final String DEFAULT_ASYNC_LIBRARY = "/app/async-profiler/lib/libasyncProfiler.so";

    @Spec
    CommandSpec spec;

    @Mixin LoggingMixin loggingMixin;

    @Mixin
    private ApiCommonSharedOptions apiCommonSharedOptions;

    Path benchmarkPath = getWorkingDirectory().resolve("jmh-benchmarks.jar");

    @Option(names = RunnerArgs.BENCHMARK_PATH, description = "Path to benchmark jar (default: ${DEFAULT-VALUE})")
    public void setBenchmarkPath(Path path) {
        if (!Files.exists(path)) {
            throw new ParameterException(spec.commandLine(),
                "Invalid benchmark path: %s".formatted(path));
        }
        this.benchmarkPath = path;
    }

    @Parameters(paramLabel = "BENCHMARK_ARGS", description = "JMH's own arguments, after --")
    List<String> benchmarkArgs = new ArrayList<>();

    @Override
    public void run() {
        BenchmarkArguments.refuseManagedOrNoRun(spec, BenchmarkType.JMH, benchmarkArgs);
        // In the order the builder used to evaluate them: the request options can refuse the run
        // (no project, a JDK not on this image) before any client is built or any storage line is logged.
        var common = apiCommonSharedOptions.getRequestOptions();
        var run = new JmhRun(benchmarkPath, benchmarkArgs, getWorkingDirectory(), asyncLibrary(),
            apiCommonSharedOptions.javaExecutable());
        var store = apiCommonSharedOptions.buildResultsStore();
        var storage = StorageService.of(apiCommonSharedOptions.getS3Options());
        new JmhSubcommandService(storage, store, common, run).executeCommand();
    }

    /** Where the image installs async-profiler, unless ASYNC_PATH says otherwise on a developer's machine. */
    private static Path asyncLibrary() {
        String asyncPath = System.getenv("ASYNC_PATH");
        return Path.of(asyncPath != null ? asyncPath : DEFAULT_ASYNC_LIBRARY);
    }
}
