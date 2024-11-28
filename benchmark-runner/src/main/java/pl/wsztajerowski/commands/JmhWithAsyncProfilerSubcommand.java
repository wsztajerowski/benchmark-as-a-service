package pl.wsztajerowski.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import pl.wsztajerowski.services.options.AsyncProfilerOptions;

import java.nio.file.Files;
import java.nio.file.Path;

import static picocli.CommandLine.Model.CommandSpec;
import static picocli.CommandLine.ParameterException;
import static picocli.CommandLine.Spec;
import static pl.wsztajerowski.commands.TestWrapper.getWorkingDirectory;
import static pl.wsztajerowski.infra.StorageServiceBuilder.getS3ServiceBuilder;
import static pl.wsztajerowski.services.JmhWithAsyncProfilerSubcommandServiceBuilder.serviceBuilder;

@Command(name = "jmh-with-async", description = "Run JHM benchmarks with Async profiler")
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

    @Option(names = {"-ap", "--async-path"}, defaultValue = "${ASYNC_PATH:-/home/ec2-user/async-profiler-2.9-linux-x64/build/libasyncProfiler.so}", description = "Path to Async profiler (default: ${DEFAULT-VALUE})")
    public void setAsyncPath(Path path) {
        if(!Files.exists(path)){
            throw new ParameterException(spec.commandLine(),
                "Invalid path to async profiler: %s".formatted(path));
        }
        this.asyncPath = path;
    }

    @Option(names = {"-ai", "--async-interval"}, description = "Profiling interval (default: ${DEFAULT-VALUE})")
    int asyncInterval = 9990;

    @Option(names = {"-aot", "--async-output-type"}, description = "Output format(s). Supported: [text, collapsed, flamegraph, tree, jfr] (default: ${DEFAULT-VALUE})")
    String asyncOutputType = "flamegraph";

    @Option(names = {"-aop", "--async-output-path"}, description = "Profiler output path (default: ${DEFAULT-VALUE})")
    Path asyncOutputPath = getWorkingDirectory().resolve("async-output");

    @Override
    public void run() {
        serviceBuilder()
            .withCommonOptions(apiCommonSharedOptions.getRequestOptions())
            .withJmhOptions(apiJmhOptions.getJmhOptions())
            .withAsyncProfilerOptions(AsyncProfilerOptions.asyncProfilerOptionsBuilder()
                .withAsyncPath(asyncPath)
                .withAsyncInterval(asyncInterval)
                .withAsyncOutputType(asyncOutputType)
                .withAsyncOutputPath(asyncOutputPath)
                .build())
            .withMongoConnectionString(apiCommonSharedOptions.getMongoConnectionString())
            .withStorageService(getS3ServiceBuilder().withS3Options(apiCommonSharedOptions.getS3Options()).build())
            .build()
            .executeCommand();
    }
}
