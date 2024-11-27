package pl.wsztajerowski.commands;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.nio.file.Path;

import static pl.wsztajerowski.infra.S3ServiceBuilder.getS3ServiceBuilder;
import static pl.wsztajerowski.services.JCStressSubcommandServiceBuilder.serviceBuilder;

@Command(name = "jcstress", description = "Run JCStress performance tests")
public class JCStressSubcommand implements Runnable {
    @Mixin
    LoggingMixin loggingMixin;

    @Mixin
    private ApiCommonSharedOptions apiCommonSharedOptions;

    @Mixin
    private ApiJCStressOptions apiJCStressOptions;

    @CommandLine.Option(names = "--benchmark-path", defaultValue = "${BENCHMARK_PATH:-stress-tests.jar}", description = "Path to JCStress benchmark jar (default: ${DEFAULT-VALUE})")
    Path benchmarkPath;

    @Override
    public void run() {
        serviceBuilder()
            .withCommonOptions(apiCommonSharedOptions.getRequestOptions())
            .withJCStressOptions(apiJCStressOptions.getValues())
            .withBenchmarkPath(benchmarkPath)
            .withS3Service(getS3ServiceBuilder().withS3Options(apiCommonSharedOptions.getS3Options()).build())
            .withMongoConnectionString(apiCommonSharedOptions.getMongoConnectionString())
            .build()
            .executeCommand();
    }
}
