package pl.wsztajerowski.commands;

import pl.wsztajerowski.baas.model.BenchmarkType;

import pl.wsztajerowski.baas.model.RunnerArgs;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.nio.file.Path;

import static pl.wsztajerowski.FileUtils.getWorkingDirectory;
import static pl.wsztajerowski.infra.StorageServiceBuilder.getS3ServiceBuilder;
import static pl.wsztajerowski.services.JCStressSubcommandServiceBuilder.serviceBuilder;

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
        serviceBuilder()
            .withCommonOptions(apiCommonSharedOptions.getRequestOptions())
            .withJCStressOptions(apiJCStressOptions.getValues())
            .withBenchmarkPath(benchmarkPath)
            .withStorageService(getS3ServiceBuilder().withS3Options(apiCommonSharedOptions.getS3Options()).build())
            .withResultsStore(apiCommonSharedOptions.buildResultsStore())
            .build()
            .executeCommand();
    }
}
