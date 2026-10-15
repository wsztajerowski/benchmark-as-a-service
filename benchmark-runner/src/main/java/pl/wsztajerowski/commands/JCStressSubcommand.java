package pl.wsztajerowski.commands;

import pl.wsztajerowski.infra.StorageService;
import pl.wsztajerowski.services.JCStressSubcommandService;
import pl.wsztajerowski.baas.model.BenchmarkType;
import pl.wsztajerowski.baas.model.RunnerArgs;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.nio.file.Path;

import static pl.wsztajerowski.FileUtils.getWorkingDirectory;

@Command(name = BenchmarkType.JCSTRESS_NAME, description = "Run JCStress performance tests")
public class JCStressSubcommand implements Runnable {
    @Mixin
    LoggingMixin loggingMixin;

    @Mixin
    private ApiCommonSharedOptions apiCommonSharedOptions;

    @Mixin
    private ApiJCStressOptions apiJCStressOptions;

    @Option(names = RunnerArgs.BENCHMARK_PATH, description = "Path to JCStress benchmark jar (default: ${DEFAULT-VALUE})")
    Path benchmarkPath = getWorkingDirectory().resolve("stress-tests.jar");

    @Override
    public void run() {
        // In the order the builder used to evaluate them: the request options can refuse the run
        // (no project) before any client is built or any storage line is logged.
        var common = apiCommonSharedOptions.getRequestOptions();
        var jcstress = apiJCStressOptions.getValues();
        var storage = StorageService.of(apiCommonSharedOptions.getS3Options());
        var store = apiCommonSharedOptions.buildResultsStore();
        new JCStressSubcommandService(storage, store, common, benchmarkPath, jcstress).executeCommand();
    }
}
