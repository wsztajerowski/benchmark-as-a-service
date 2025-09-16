package pl.wsztajerowski.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.Map;

import static picocli.CommandLine.Model.CommandSpec;
import static picocli.CommandLine.Spec;
import static pl.wsztajerowski.infra.StorageServiceBuilder.getS3ServiceBuilder;
import static pl.wsztajerowski.services.JmhWithProfilerSubcommandServiceBuilder.serviceBuilder;

@Command(name = "jmh-with-prof", description = "Run JHM benchmarks with profiler")
public class JmhWithProfilerSubcommand implements Runnable {
    @Spec
    CommandSpec spec;

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
        serviceBuilder()
            .withCommonOptions(apiCommonSharedOptions.getRequestOptions())
            .withJmhOptions(apiJmhOptions.getJmhOptions())
            .withProfilerOptions(profilerOptions)
            .withMongoConnectionString(apiCommonSharedOptions.getMongoConnectionString())
            .withStorageService(getS3ServiceBuilder().withS3Options(apiCommonSharedOptions.getS3Options()).build())
            .build()
            .executeCommand();
    }
}
