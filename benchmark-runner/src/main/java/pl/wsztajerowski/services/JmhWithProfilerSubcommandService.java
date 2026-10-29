package pl.wsztajerowski.services;

import pl.wsztajerowski.baas.model.BenchmarkType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.JavaWonderlandException;
import pl.wsztajerowski.baas.model.StoredMeasurement;
import pl.wsztajerowski.infra.ResultsStore;
import pl.wsztajerowski.infra.StorageService;
import pl.wsztajerowski.process.BenchmarkProcessBuilder;
import pl.wsztajerowski.results.JmhRunResults;
import pl.wsztajerowski.options.CommonSharedOptions;
import pl.wsztajerowski.options.JmhOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;
import static java.text.MessageFormat.format;
import static pl.wsztajerowski.process.JmhBenchmarkProcessBuilderFactory.prepopulatedJmhBenchmarkProcessBuilder;

public class JmhWithProfilerSubcommandService {
    private static final Logger logger = LoggerFactory.getLogger(JmhWithProfilerSubcommandService.class);
    private final CommonSharedOptions commonOptions;
    private final JmhOptions jmhOptions;
    private final StorageService storageService;
    private final ResultsStore resultsStore;
    private final Map<String, String> profilerOptions;
    private final Path outputPath;

    public JmhWithProfilerSubcommandService(StorageService storageService, ResultsStore resultsStore, CommonSharedOptions commonOptions, JmhOptions jmhOptions, Map<String, String> profilerOptions) {
        requireNonNull(storageService, "Please provide a storage service");
        requireNonNull(resultsStore, "Please provide a results store");
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
            Files.createDirectories(jmhOptions.outputOptions().machineReadableOutput().toAbsolutePath().getParent());
            BenchmarkProcessBuilder benchmarkProcessBuilder = prepopulatedJmhBenchmarkProcessBuilder(jmhOptions);
            profilerOptions.forEach((profilerName, profilerOptions) ->
                benchmarkProcessBuilder.addArgumentWithValue("-prof", createProfilerCommand(profilerName, profilerOptions)));
            int exitCode = benchmarkProcessBuilder
                .buildAndStartProcess()
                .waitFor();

            logger.info("Saving benchmark profiler(s) process output on S3");
            storageService
                .saveFile(outputPath.resolve(BenchmarkType.JMH_WITH_PROF.processOutputName()), jmhOptions.outputOptions().processOutput());
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
        // -prof writes into the result tree itself, so the local and storage paths are one.
        ProfilerArtifacts.upload(storageService, outputPath, outputPath);

        Path resultJson = jmhOptions.outputOptions().machineReadableOutput();
        // Uploaded before the store write, so a job that fails at the store keeps its result.
        Path resultJsonKey = JmhRunResults.resultJsonKey(commonOptions);
        logger.info("Saving verbatim JMH result JSON: {}", resultJsonKey);
        storageService.saveFile(resultJsonKey, resultJson);
        List<StoredMeasurement> measurements = JmhRunResults.map(commonOptions, resultJson);

        logger.info("Storing {} measurement(s) for request {}", measurements.size(), commonOptions.jobId());
        resultsStore.write(measurements);
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
