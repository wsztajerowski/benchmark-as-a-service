package pl.wsztajerowski.services;

import pl.wsztajerowski.baas.model.BenchmarkType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.JavaWonderlandException;
import pl.wsztajerowski.baas.model.StoredMeasurement;
import pl.wsztajerowski.infra.ResultsStore;
import pl.wsztajerowski.infra.StorageService;
import pl.wsztajerowski.results.JmhRunResults;
import pl.wsztajerowski.options.CommonSharedOptions;
import pl.wsztajerowski.options.JmhOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.util.Objects.requireNonNull;
import static java.text.MessageFormat.format;
import static pl.wsztajerowski.process.JmhBenchmarkProcessBuilderFactory.prepopulatedJmhBenchmarkProcessBuilder;

public class JmhSubcommandService {
    private static final Logger logger = LoggerFactory.getLogger(JmhSubcommandService.class);
    private final CommonSharedOptions commonOptions;
    private final JmhOptions jmhOptions;
    private final StorageService storageService;
    private final ResultsStore resultsStore;

    public JmhSubcommandService(StorageService storageService, ResultsStore resultsStore, CommonSharedOptions commonOptions, JmhOptions jmhOptions) {
        requireNonNull(storageService, "Please provide a storage service");
        requireNonNull(resultsStore, "Please provide a results store");
        this.storageService = storageService;
        this.resultsStore = resultsStore;
        this.commonOptions = commonOptions;
        this.jmhOptions = jmhOptions;
    }

    public void executeCommand() {
        Path outputPath = commonOptions.resultPath();
        logger.info("Running JMH. Output path: {}", outputPath);
        try {
            Files.createDirectories(jmhOptions.outputOptions().machineReadableOutput().toAbsolutePath().getParent());
            int exitCode = prepopulatedJmhBenchmarkProcessBuilder(jmhOptions)
                .buildAndStartProcess()
                .waitFor();

            logger.info("Saving benchmark process output");
            storageService
                .saveFile(outputPath.resolve(BenchmarkType.JMH.processOutputName()), jmhOptions.outputOptions().processOutput());

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
        Path resultJson = jmhOptions.outputOptions().machineReadableOutput();
        // Uploaded before the store write, so a job that fails at the store keeps its result.
        Path resultJsonKey = JmhRunResults.resultJsonKey(commonOptions);
        logger.info("Saving verbatim JMH result JSON: {}", resultJsonKey);
        storageService.saveFile(resultJsonKey, resultJson);
        List<StoredMeasurement> measurements = JmhRunResults.map(commonOptions, resultJson);

        logger.info("Storing {} measurement(s) for request {}", measurements.size(), commonOptions.jobId());
        resultsStore.write(measurements);
    }
}
