package pl.wsztajerowski.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import static pl.wsztajerowski.infra.StorageServiceBuilder.getS3ServiceBuilder;
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
            .withStorageService(getS3ServiceBuilder()
                .withS3Options(apiCommonSharedOptions.getS3Options())
                .build())
            .build()
            .executeCommand();
    }
}
