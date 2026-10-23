package pl.wsztajerowski.services;

import pl.wsztajerowski.baas.model.BenchmarkType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.JavaWonderlandException;
import pl.wsztajerowski.baas.model.StoredMeasurement;
import pl.wsztajerowski.entities.jmh.JmhResult;
import pl.wsztajerowski.infra.ResultsStore;
import pl.wsztajerowski.infra.StorageService;
import pl.wsztajerowski.results.JmhRunResults;
import pl.wsztajerowski.options.AsyncProfilerOptions;
import pl.wsztajerowski.options.CommonSharedOptions;
import pl.wsztajerowski.options.JmhOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;
import static java.text.MessageFormat.format;
import static pl.wsztajerowski.infra.ResultLoaderService.getResultLoaderService;
import static pl.wsztajerowski.process.JmhBenchmarkProcessBuilderFactory.prepopulatedJmhBenchmarkProcessBuilder;
import static pl.wsztajerowski.services.JmhUtils.getProfilerOutputDirSuffix;

public class JmhWithAsyncProfilerSubcommandService {
    private static final Logger logger = LoggerFactory.getLogger(JmhWithAsyncProfilerSubcommandService.class);
    private final CommonSharedOptions commonOptions;
    private final JmhOptions jmhOptions;
    private final StorageService storageService;
    private final ResultsStore resultsStore;
    private final AsyncProfilerOptions asyncProfilerOptions;
    private final Path outputPath;

    public JmhWithAsyncProfilerSubcommandService(StorageService storageService, ResultsStore resultsStore, CommonSharedOptions commonOptions, JmhOptions jmhOptions, AsyncProfilerOptions asyncProfilerOptions) {
        requireNonNull(storageService, "Please provide a storage service");
        requireNonNull(resultsStore, "Please provide a results store");
        this.storageService = storageService;
        this.resultsStore = resultsStore;
        this.commonOptions = commonOptions;
        this.jmhOptions = jmhOptions;
        this.asyncProfilerOptions = asyncProfilerOptions;
        this.outputPath = commonOptions.resultPath();
    }

    public void executeCommand() {
        // Build process
        logger.info("Running JMH with async profiler. Output path: {}", outputPath);
        try {
            Files.createDirectories(jmhOptions.outputOptions().machineReadableOutput().toAbsolutePath().getParent());
            int exitCode = prepopulatedJmhBenchmarkProcessBuilder(jmhOptions)
                .addArgumentWithValue("-prof", createAsyncCommand())
                .buildAndStartProcess()
                .waitFor();

            logger.info("Saving benchmark process output on S3");
            storageService
                .saveFile(outputPath.resolve(BenchmarkType.JMH_WITH_ASYNC.processOutputName()), jmhOptions.outputOptions().processOutput());
            // Covers async-profiler's own logs too: its output directory sits below the working
            // directory, so they land under logs/async-output/.
            JobLogs.upload(storageService, outputPath);

            if (exitCode != 0) {
                logger.error("Jmh process exited with exit code: {}", exitCode);
                logger.info("Benchmark process logs:\n{}", Files.readString(jmhOptions.outputOptions().processOutput()));
                throw new JavaWonderlandException(format("Benchmark process exit with non-zero code: {0}", exitCode));
            }
        } catch (InterruptedException | IOException e) {
            throw new JavaWonderlandException(e);
        }

        logger.info("Processing JMH results: {}", jmhOptions.outputOptions().machineReadableOutput());
        uploadProfilerArtifacts();

        Path resultJson = jmhOptions.outputOptions().machineReadableOutput();
        // Uploaded before the store write, so a job that fails at the store keeps its result.
        Path resultJsonKey = JmhRunResults.resultJsonKey(commonOptions);
        logger.info("Saving verbatim JMH result JSON: {}", resultJsonKey);
        storageService.saveFile(resultJsonKey, resultJson);
        List<StoredMeasurement> measurements = JmhRunResults.map(commonOptions, resultJson, this::profilerOutputPathFor);

        logger.info("Storing {} measurement(s) for request {}", measurements.size(), commonOptions.jobId());
        resultsStore.write(measurements);
    }

    /**
     * The S3 prefix holding one result's profiling artifacts. Recorded on the measurement so a
     * reader can list it, rather than re-deriving JMH's mode-dependent directory suffix elsewhere.
     * Note this is the storage prefix, not the local one — async-profiler writes under its own
     * output path and the files are uploaded into the job's result path.
     */
    private String profilerOutputPathFor(JmhResult jmhResult) {
        return outputPath.resolve(benchmarkDirName(jmhResult)).toString();
    }

    private static String benchmarkDirName(JmhResult jmhResult) {
        return jmhResult.benchmark() + getProfilerOutputDirSuffix(jmhResult.mode());
    }

    private void uploadProfilerArtifacts() {
        for (JmhResult jmhResult : getResultLoaderService().loadJmhResults(jmhOptions.outputOptions().machineReadableOutput())) {
            Path localDir = asyncProfilerOptions.asyncOutputPath().resolve(benchmarkDirName(jmhResult));
            Path storageDir = Path.of(profilerOutputPathFor(jmhResult));
            ProfilerArtifacts.upload(storageService, localDir, storageDir);
        }
    }

    private String createAsyncCommand() {
        String additionalParams = Optional.ofNullable(asyncProfilerOptions
            .asyncAdditionalOptions())
            .orElse(Collections.emptyMap())
            .entrySet()
            .stream()
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(Collectors.joining(";", ";", ""));
        return "async:libPath=%s;output=%s;dir=%s;event=%s;interval=%d%s;verbose=true".formatted(
            asyncProfilerOptions.asyncPath(),
            asyncProfilerOptions.asyncOutputType(),
            asyncProfilerOptions.asyncOutputPath().toAbsolutePath(),
            asyncProfilerOptions.asyncEvent(),
            asyncProfilerOptions.asyncInterval(),
            additionalParams);
    }
}
