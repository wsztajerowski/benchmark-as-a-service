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

    /**
     * Where a JVM looks for {@code System.loadLibrary}: Linux's reads {@code LD_LIBRARY_PATH},
     * macOS's {@code JAVA_LIBRARY_PATH} — not {@code DYLD_LIBRARY_PATH}, which SIP strips when
     * {@code java} is {@code /usr/bin/java}. Both are set on every OS, so nothing depends on which
     * one a JVM reads; JMH's async-profiler falls back to {@code loadLibrary} without a {@code libPath}.
     */
    static final List<String> LIBRARY_PATH_VARIABLES = List.of("LD_LIBRARY_PATH", "JAVA_LIBRARY_PATH");

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
     * reads back, {@code -foe true} unless the user set {@code -foe} (PT2), then the user's arguments
     * as given — except that every {@code async} and
     * {@code jfr} profiler is pointed at {@link JmhRun#profilerOutput()}, after the user's own keys.
     * The baked async-profiler's directory is on the process's library path, for the forks and for a
     * profiler listing alike, which asks for no result file and gets none.
     */
    BenchmarkProcessBuilder jmhProcess() {
        BenchmarkProcessBuilder builder = benchmarkProcessBuilder(run.java(), run.benchmarkPath());
        Path asyncDirectory = run.asyncPath().toAbsolutePath().getParent();
        LIBRARY_PATH_VARIABLES.forEach(name -> builder.prependToPathVariable(name, asyncDirectory));
        if (isProfilerListing()) {
            run.arguments().forEach(builder::addArgument);
            return builder;
        }
        builder
            .addArgumentWithValue("-rf", "json")
            .addArgumentWithValue("-rff", run.resultFile());
        if (defaultsFailOnError()) {
            builder.addArgumentWithValue("-" + BenchmarkArgs.FAIL_ON_ERROR, "true");
        }
        BenchmarkArgs.of(BenchmarkType.JMH)
            .withProfilerOptions(run.arguments(), this::managedProfilerOptions)
            .forEach(builder::addArgument);
        return builder;
    }

    private boolean defaultsFailOnError() {
        return BenchmarkArgs.of(BenchmarkType.JMH).defaultsFailOnError(run.arguments());
    }

    private boolean isProfilerListing() {
        return BenchmarkArgs.of(BenchmarkType.JMH).isProfilerListing(run.arguments());
    }

    private String managedProfilerOptions(BenchmarkArgs.Profiler profiler) {
        return switch (profiler.canonicalName()) {
            case "async" -> {
                requireAsyncLibrary();
                yield "dir=" + run.profilerOutput().toAbsolutePath();
            }
            case "jfr" -> "dir=" + run.profilerOutput().toAbsolutePath();
            default -> null;
        };
    }

    /** Checked only when a job asks for async-profiler, so a job without it never needs the library. */
    private void requireAsyncLibrary() {
        if (!Files.exists(run.asyncPath())) {
            throw new JavaWonderlandException(
                "-prof async needs async-profiler, which is not at %s (ASYNC_PATH moves it)".formatted(run.asyncPath()));
        }
    }

    public void executeCommand() {
        Path outputPath = commonOptions.resultPath();
        logger.info("Running JMH. Output path: {}", outputPath);
        if (defaultsFailOnError()) {
            logger.info("Running with -foe true, BaaS's default: a failed benchmark fails the job and "
                + "no measurement is stored. Pass -foe false after -- to keep the ones that succeeded");
        }
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
            // Before the exit check: a job that failed after profiling — one benchmark erroring, or
            // -foe — still has the profiles of what ran, and those are what it most needs. Profilers
            // write under their dir; the tree lands in the job's result path, each benchmark's
            // directory under the name JMH gave it. Nothing there (no async or jfr) uploads nothing.
            ProfilerArtifacts.upload(storageService, run.profilerOutput(), outputPath);

            if (exitCode != 0) {
                logger.error("Jmh process exited with exit code: {}", exitCode);
                logger.info("Benchmark process logs:\n{}", Files.readString(run.processOutput()));
                if (defaultsFailOnError()) {
                    logger.error("A benchmark failed under -foe true, BaaS's default. Pass -foe false after -- "
                        + "to store the benchmarks that succeeded");
                }
                throw new JavaWonderlandException(format("Benchmark process exit with non-zero code: {0}", exitCode));
            }
        } catch (InterruptedException | IOException e) {
            throw new JavaWonderlandException(e);
        }

        if (isProfilerListing()) {
            // The listing is the process output, already uploaded; JMH wrote no result to store.
            logger.info("Listed the profilers of this host; nothing was measured");
            return;
        }

        logger.info("Processing JMH results: {}", run.resultFile());
        Path resultJson = run.resultFile();
        // Uploaded before the store write, so a job that fails at the store keeps its result.
        Path resultJsonKey = JmhRunResults.resultJsonKey(commonOptions);
        logger.info("Saving verbatim JMH result JSON: {}", resultJsonKey);
        storageService.saveFile(resultJsonKey, resultJson);
        List<StoredMeasurement> measurements = JmhRunResults.map(commonOptions, resultJson);
        if (measurements.isEmpty()) {
            // JMH exits 0 with an empty result when every benchmark failed under -foe false: without
            // this the job ended completed with nothing stored (PT2). The result JSON is uploaded.
            throw new JavaWonderlandException(
                "JMH exited 0 but wrote no measurement: every benchmark failed. See %s"
                    .formatted(BenchmarkType.JMH.processOutputName()));
        }

        logger.info("Storing {} measurement(s) for request {}", measurements.size(), commonOptions.jobId());
        resultsStore.write(measurements);
    }
}
