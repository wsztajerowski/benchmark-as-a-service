package pl.wsztajerowski.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.JavaWonderlandException;
import pl.wsztajerowski.baas.model.BenchmarkArgs;
import pl.wsztajerowski.baas.model.BenchmarkType;
import pl.wsztajerowski.baas.model.StoredMeasurement;
import pl.wsztajerowski.infra.ResultsStore;
import pl.wsztajerowski.infra.StorageService;
import pl.wsztajerowski.options.CommonSharedOptions;
import pl.wsztajerowski.options.JmhRun;
import pl.wsztajerowski.process.BenchmarkProcessBuilder;
import pl.wsztajerowski.results.JmhRunResults;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.text.MessageFormat.format;
import static java.util.Objects.requireNonNull;
import static pl.wsztajerowski.process.BenchmarkProcessBuilder.benchmarkProcessBuilder;

public class JmhSubcommandService {
    private static final Logger logger = LoggerFactory.getLogger(JmhSubcommandService.class);
    private final CommonSharedOptions commonOptions;
    private final JmhRun run;
    private final StorageService storageService;
    private final ResultsStore resultsStore;

    public JmhSubcommandService(StorageService storageService, ResultsStore resultsStore, CommonSharedOptions commonOptions, JmhRun run) {
        requireNonNull(storageService, "Please provide a storage service");
        requireNonNull(resultsStore, "Please provide a results store");
        this.storageService = storageService;
        this.resultsStore = resultsStore;
        this.commonOptions = commonOptions;
        this.run = run;
    }

    /**
     * JMH's command line, without the output redirection: the result format and file the runner
     * reads back, then the user's arguments as given — except that every {@code async} and
     * {@code jfr} profiler is pointed at {@link JmhRun#profilerOutput()}, and async at the baked
     * library, after the user's own keys.
     */
    BenchmarkProcessBuilder jmhProcess() {
        BenchmarkProcessBuilder builder = benchmarkProcessBuilder(run.benchmarkPath())
            .addArgumentWithValue("-rf", "json")
            .addArgumentWithValue("-rff", run.resultFile());
        BenchmarkArgs.of(BenchmarkType.JMH)
            .withProfilerOptions(run.arguments(), this::managedProfilerOptions)
            .forEach(builder::addArgument);
        return builder;
    }

    private String managedProfilerOptions(BenchmarkArgs.Profiler profiler) {
        String dir = "dir=" + run.profilerOutput().toAbsolutePath();
        return switch (profiler.name()) {
            case "async" -> dir + ";libPath=" + asyncLibrary();
            case "jfr" -> dir;
            default -> null;
        };
    }

    /** Checked only when a job asks for async-profiler, so a job without it never needs the library. */
    private Path asyncLibrary() {
        if (!Files.exists(run.asyncPath())) {
            throw new JavaWonderlandException(
                "-prof async needs async-profiler, which is not at %s (--async-path, or ASYNC_PATH)".formatted(run.asyncPath()));
        }
        return run.asyncPath().toAbsolutePath();
    }

    public void executeCommand() {
        Path outputPath = commonOptions.resultPath();
        logger.info("Running JMH. Output path: {}", outputPath);
        try {
            Files.createDirectories(run.resultFile().toAbsolutePath().getParent());
            int exitCode = jmhProcess()
                .withOutputPath(run.processOutput())
                .buildAndStartProcess()
                .waitFor();

            logger.info("Saving benchmark process output");
            storageService
                .saveFile(outputPath.resolve(BenchmarkType.JMH.processOutputName()), run.processOutput());
            // Covers async-profiler's own logs too: its output directory sits below the working
            // directory, so they land under logs/profiler-output/.
            JobLogs.upload(storageService, outputPath);

            if (exitCode != 0) {
                logger.error("Jmh process exited with exit code: {}", exitCode);
                logger.info("Benchmark process logs:\n{}", Files.readString(run.processOutput()));
                throw new JavaWonderlandException(format("Benchmark process exit with non-zero code: {0}", exitCode));
            }
        } catch (InterruptedException | IOException e) {
            throw new JavaWonderlandException(e);
        }

        // Profilers write under their dir; the tree lands in the job's result path, each benchmark's
        // directory under the name JMH gave it. Nothing there (no async or jfr) uploads nothing.
        ProfilerArtifacts.upload(storageService, run.profilerOutput(), outputPath);

        logger.info("Processing JMH results: {}", run.resultFile());
        Path resultJson = run.resultFile();
        // Uploaded before the store write, so a job that fails at the store keeps its result.
        Path resultJsonKey = JmhRunResults.resultJsonKey(commonOptions);
        logger.info("Saving verbatim JMH result JSON: {}", resultJsonKey);
        storageService.saveFile(resultJsonKey, resultJson);
        List<StoredMeasurement> measurements = JmhRunResults.map(commonOptions, resultJson);

        logger.info("Storing {} measurement(s) for request {}", measurements.size(), commonOptions.jobId());
        resultsStore.write(measurements);
    }
}
