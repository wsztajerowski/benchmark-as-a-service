package pl.wsztajerowski.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import pl.wsztajerowski.infra.S3ServiceBuilder;

import static pl.wsztajerowski.infra.S3ServiceBuilder.getS3ServiceBuilder;
import static pl.wsztajerowski.services.JmhSubcommandServiceBuilder.serviceBuilder;

@Command(name = "jmh", description = "Run JHM benchmarks")
public class JmhSubcommand implements Runnable {
    @Mixin LoggingMixin loggingMixin;

    @Mixin
    private ApiJmhOptions apiJmhOptions;

    @Mixin
    private ApiCommonSharedOptions apiCommonSharedOptions;

    @Override
    public void run() {
        serviceBuilder()
            .withCommonOptions(apiCommonSharedOptions.getRequestOptions())
            .withJmhOptions(apiJmhOptions.getJmhOptions())
            .withMongoConnectionString(apiCommonSharedOptions.getMongoConnectionString())
            .withS3Service(getS3ServiceBuilder()
                .withS3Options(apiCommonSharedOptions.getS3Options())
                .build())
            .build()
            .executeCommand();
    }
}
