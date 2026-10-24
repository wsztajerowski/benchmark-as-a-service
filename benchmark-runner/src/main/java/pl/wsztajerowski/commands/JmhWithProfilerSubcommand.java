package pl.wsztajerowski.commands;

import pl.wsztajerowski.infra.StorageService;
import pl.wsztajerowski.services.JmhWithProfilerSubcommandService;
import pl.wsztajerowski.baas.model.BenchmarkType;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.Map;

@Command(name = BenchmarkType.JMH_WITH_PROF_NAME, description = "Run JHM benchmarks with profiler")
public class JmhWithProfilerSubcommand implements Runnable {
    @Mixin
    LoggingMixin loggingMixin;

    @Mixin
    private ApiJmhOptions apiJmhOptions;

    @Mixin
    private ApiCommonSharedOptions apiCommonSharedOptions;

    @Option(
        names = {"-pr", "--profiler"},
        description = "Profiler options as a map in format: PROFILER_NAME=PROFILER_OPTIONS, where PROFILER_OPTIONS is a optional map of profiler's options. Example: option1=value1;option2=value2",
        mapFallbackValue = ""
    )
    Map<String, String> profilerOptions;

    @Override
    public void run() {
        // In the order the builder used to evaluate them: the request options can refuse the run
        // (no project) before any client is built or any storage line is logged.
        var common = apiCommonSharedOptions.getRequestOptions();
        var jmh = apiJmhOptions.getJmhOptions();
        var store = apiCommonSharedOptions.buildResultsStore();
        var storage = StorageService.of(apiCommonSharedOptions.getS3Options());
        new JmhWithProfilerSubcommandService(storage, store, common, jmh, profilerOptions).executeCommand();
    }
}
