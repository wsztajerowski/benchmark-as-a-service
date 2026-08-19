package pl.wsztajerowski.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.JavaWonderlandException;
import pl.wsztajerowski.baas.model.StoredMeasurement;
import pl.wsztajerowski.entities.jmh.JmhResult;
import pl.wsztajerowski.infra.ResultsStore;
import pl.wsztajerowski.infra.StorageService;
import pl.wsztajerowski.results.JmhRunResults;
import pl.wsztajerowski.services.options.AsyncProfilerOptions;
import pl.wsztajerowski.services.options.CommonSharedOptions;
import pl.wsztajerowski.services.options.JmhOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.file.Files.list;
import static java.text.MessageFormat.format;
import static pl.wsztajerowski.FileUtils.ensurePathExists;
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

    JmhWithAsyncProfilerSubcommandService(StorageService storageService, ResultsStore resultsStore, CommonSharedOptions commonOptions, JmhOptions jmhOptions, AsyncProfilerOptions asyncProfilerOptions) {
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
            ensurePathExists(jmhOptions.outputOptions().machineReadableOutput());
            int exitCode = prepopulatedJmhBenchmarkProcessBuilder(jmhOptions)
                .addArgumentWithValue("-prof", createAsyncCommand())
                .buildAndStartProcess()
                .waitFor();

            logger.info("Saving benchmark process output on S3");
            storageService
                .saveFile(outputPath.resolve("jmh-with-async-output.txt"), jmhOptions.outputOptions().processOutput());

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

        logger.info("Saving JMH logs on S3");
        try (Stream<Path> paths = list(asyncProfilerOptions.asyncOutputPath())){
            paths
                .filter(f -> f.toString().endsWith("log"))
                .forEach(path -> {
                    Path s3Key = outputPath.resolve("logs").resolve(path.getFileName());
                    storageService
                        .saveFile(s3Key, path);
                });
        } catch (IOException e) {
            throw new JavaWonderlandException(e);
        }

        List<StoredMeasurement> measurements = JmhRunResults.uploadJsonAndMap(
            storageService, commonOptions, jmhOptions.outputOptions().machineReadableOutput(),
            this::profilerOutputPathFor);

        logger.info("Storing {} measurement(s) for request {}", measurements.size(), commonOptions.requestId());
        resultsStore.write(measurements);
    }

    /**
     * The S3 prefix holding one result's profiling artifacts. Recorded on the measurement so a
     * reader can list it, rather than re-deriving JMH's mode-dependent directory suffix elsewhere.
     * Note this is the storage prefix, not the local one — async-profiler writes under its own
     * output path and the files are uploaded into the run's result path.
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
            try (Stream<Path> paths = list(localDir)) {
                paths
                    .forEach(path -> {
                        Path storagePath = storageDir.resolve(path.getFileName());
                        logger.info("Saving profiler output: {}", storagePath);
                        storageService
                            .saveFile(storagePath, path);
                    });
            } catch (IOException e) {
                throw new JavaWonderlandException(e);
            }
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
