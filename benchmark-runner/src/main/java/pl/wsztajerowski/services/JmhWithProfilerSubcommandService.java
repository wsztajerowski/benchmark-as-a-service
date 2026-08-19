package pl.wsztajerowski.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.JavaWonderlandException;
import pl.wsztajerowski.baas.model.StoredMeasurement;
import pl.wsztajerowski.entities.jmh.JmhResult;
import pl.wsztajerowski.infra.ResultsStore;
import pl.wsztajerowski.infra.StorageService;
import pl.wsztajerowski.process.BenchmarkProcessBuilder;
import pl.wsztajerowski.results.JmhRunResults;
import pl.wsztajerowski.services.options.CommonSharedOptions;
import pl.wsztajerowski.services.options.JmhOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.file.Files.list;
import static java.text.MessageFormat.format;
import static pl.wsztajerowski.FileUtils.ensurePathExists;
import static pl.wsztajerowski.infra.ResultLoaderService.getResultLoaderService;
import static pl.wsztajerowski.process.JmhBenchmarkProcessBuilderFactory.prepopulatedJmhBenchmarkProcessBuilder;
import static pl.wsztajerowski.services.JmhUtils.getProfilerOutputDirSuffix;

public class JmhWithProfilerSubcommandService {
    private static final Logger logger = LoggerFactory.getLogger(JmhWithProfilerSubcommandService.class);
    private final CommonSharedOptions commonOptions;
    private final JmhOptions jmhOptions;
    private final StorageService storageService;
    private final ResultsStore resultsStore;
    private final Map<String, String> profilerOptions;
    private final Path outputPath;

    JmhWithProfilerSubcommandService(StorageService storageService, ResultsStore resultsStore, CommonSharedOptions commonOptions, JmhOptions jmhOptions, Map<String, String> profilerOptions) {
        this.storageService = storageService;
        this.resultsStore = resultsStore;
        this.commonOptions = commonOptions;
        this.jmhOptions = jmhOptions;
        this.profilerOptions = profilerOptions;
        this.outputPath = commonOptions.resultPath();
    }

    public void executeCommand() {
        // Build process
        logger.info("Running JMH with profiler(s). Output path: {}", outputPath);
        try {
            ensurePathExists(jmhOptions.outputOptions().machineReadableOutput());
            BenchmarkProcessBuilder benchmarkProcessBuilder = prepopulatedJmhBenchmarkProcessBuilder(jmhOptions);
            profilerOptions.forEach((profilerName, profilerOptions) ->
                benchmarkProcessBuilder.addArgumentWithValue("-prof", createProfilerCommand(profilerName, profilerOptions)));
            int exitCode = benchmarkProcessBuilder
                .buildAndStartProcess()
                .waitFor();

            logger.info("Saving benchmark profiler(s) process output on S3");
            storageService
                .saveFile(outputPath.resolve("jmh-profiler-output.txt"), jmhOptions.outputOptions().processOutput());

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

        List<StoredMeasurement> measurements = JmhRunResults.uploadJsonAndMap(
            storageService, commonOptions, jmhOptions.outputOptions().machineReadableOutput(),
            this::profilerOutputPathFor);

        logger.info("Storing {} measurement(s) for request {}", measurements.size(), commonOptions.requestId());
        resultsStore.write(measurements);
    }

    /**
     * The S3 prefix holding one result's profiling artifacts. Recorded on the measurement so a
     * reader can list it, rather than re-deriving JMH's mode-dependent directory suffix elsewhere.
     */
    private String profilerOutputPathFor(JmhResult jmhResult) {
        return outputPath
            .resolve(jmhResult.benchmark() + getProfilerOutputDirSuffix(jmhResult.mode()))
            .toString();
    }

    private void uploadProfilerArtifacts() {
        for (JmhResult jmhResult : getResultLoaderService().loadJmhResults(jmhOptions.outputOptions().machineReadableOutput())) {
            Path storageDir = Path.of(profilerOutputPathFor(jmhResult));
            try (Stream<Path> paths = list(storageDir)) {
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

    private String createProfilerCommand(String profilerName, String profilerOptions) {
        String outputOption = Optional.of(profilerName)
            .map(this::getProfilerOutputOptionName)
            .map(outputOptionName -> "%s=%s".formatted(outputOptionName, outputPath.toString()))
            .orElse("");
        String options = Stream.of(profilerOptions, outputOption)
            .filter(this::isNotBlank)
            .collect(Collectors.joining(";"));
        return isNotBlank(options)? "%s:%s".formatted(profilerName, options) : profilerName;
    }

    private boolean isNotBlank(String string) {
        return string != null && !string.trim().isEmpty();
    }

    private String getProfilerOutputOptionName(String profilerName) {
        return switch (profilerName) {
            case "async", "jfr" -> "dir";
            default -> null;
        };
    }
}
