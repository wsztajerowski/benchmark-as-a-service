package pl.wsztajerowski.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import pl.wsztajerowski.baas.model.BenchmarkType;
import pl.wsztajerowski.baas.model.RunnerArgs;
import pl.wsztajerowski.infra.StorageService;
import pl.wsztajerowski.options.JCStressRun;
import pl.wsztajerowski.services.JCStressSubcommandService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static pl.wsztajerowski.FileUtils.getWorkingDirectory;

@Command(name = BenchmarkType.JCSTRESS_NAME,
    description = "Run JCStress tests. Everything after -- reaches the test JAR as given, except -r, "
        + "which the runner sets.")
public class JCStressSubcommand implements Runnable {
    @Spec
    CommandSpec spec;

    @Mixin
    LoggingMixin loggingMixin;

    @Mixin
    private ApiCommonSharedOptions apiCommonSharedOptions;

    @Option(names = RunnerArgs.BENCHMARK_PATH, description = "Path to JCStress benchmark jar (default: ${DEFAULT-VALUE})")
    Path benchmarkPath = getWorkingDirectory().resolve("stress-tests.jar");

    @Parameters(paramLabel = "BENCHMARK_ARGS", description = "JCStress's own arguments, after --")
    List<String> benchmarkArgs = new ArrayList<>();

    @Override
    public void run() {
        BenchmarkArguments.refuseManagedOrNoRun(spec, BenchmarkType.JCSTRESS, benchmarkArgs);
        // In the order the builder used to evaluate them: the request options can refuse the run
        // (no project, a JDK not on this image) before any client is built or any storage line is logged.
        var common = apiCommonSharedOptions.getRequestOptions();
        var run = new JCStressRun(benchmarkPath, benchmarkArgs, getWorkingDirectory(),
            apiCommonSharedOptions.javaExecutable());
        var storage = StorageService.of(apiCommonSharedOptions.getS3Options());
        var store = apiCommonSharedOptions.buildResultsStore();
        new JCStressSubcommandService(storage, store, common, run).executeCommand();
    }
}
