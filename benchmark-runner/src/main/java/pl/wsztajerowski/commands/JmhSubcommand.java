package pl.wsztajerowski.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

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
            .withCommonOptions(apiCommonSharedOptions.getValues())
            .withJmhOptions(apiJmhOptions.getJmhOptions())
            .withMongoConnectionString(apiCommonSharedOptions.getMongoConnectionString())
            .withDefaultS3Service(apiCommonSharedOptions.getS3ServiceEndpoint())
            .build()
            .executeCommand();
    }
}
