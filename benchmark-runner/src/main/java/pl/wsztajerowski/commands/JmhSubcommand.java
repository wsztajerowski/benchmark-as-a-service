package pl.wsztajerowski.commands;

import pl.wsztajerowski.infra.StorageService;
import pl.wsztajerowski.services.JmhSubcommandService;
import pl.wsztajerowski.baas.model.BenchmarkType;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

@Command(name = BenchmarkType.JMH_NAME, description = "Run JHM benchmarks")
public class JmhSubcommand implements Runnable {
    @Mixin LoggingMixin loggingMixin;

    @Mixin
    private ApiJmhOptions apiJmhOptions;

    @Mixin
    private ApiCommonSharedOptions apiCommonSharedOptions;

    @Override
    public void run() {
        // In the order the builder used to evaluate them: the request options can refuse the run
        // (no project) before any client is built or any storage line is logged.
        var common = apiCommonSharedOptions.getRequestOptions();
        var jmh = apiJmhOptions.getJmhOptions();
        var store = apiCommonSharedOptions.buildResultsStore();
        var storage = StorageService.of(apiCommonSharedOptions.getS3Options());
        new JmhSubcommandService(storage, store, common, jmh).executeCommand();
    }
}
