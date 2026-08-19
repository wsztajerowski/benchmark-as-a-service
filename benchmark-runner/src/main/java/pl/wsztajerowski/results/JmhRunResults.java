package pl.wsztajerowski.results;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.baas.model.StoredMeasurement;
import pl.wsztajerowski.entities.jmh.JmhResult;
import pl.wsztajerowski.infra.StorageService;
import pl.wsztajerowski.services.options.CommonSharedOptions;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static pl.wsztajerowski.infra.ResultLoaderService.getResultLoaderService;

/**
 * Shared by the three JMH-flavoured services: upload the verbatim result JSON, then map every
 * result in it into the stored shape.
 *
 * <p>The stored item is deliberately thin — {@code rawData} and {@code scorePercentiles} are
 * dropped — so the unmodified JMH JSON has to go somewhere retrievable. It goes to S3 under the
 * run's result path, and every measurement from that run points at it via {@code resultJsonKey}.
 *
 * <p>The upload happens before the store write, because a run that fails at the store must still
 * leave its artifacts behind.
 */
public final class JmhRunResults {
    private static final Logger logger = LoggerFactory.getLogger(JmhRunResults.class);

    private JmhRunResults() {}

    /** For runs with no profiler: every measurement gets a null profiler-output prefix. */
    public static final Function<JmhResult, String> NO_PROFILER_OUTPUT = result -> null;

    public static List<StoredMeasurement> uploadJsonAndMap(
        StorageService storageService, CommonSharedOptions commonOptions, Path machineReadableOutput,
        Function<JmhResult, String> profilerOutputPath) {

        Path outputPath = commonOptions.resultPath();
        Path resultJsonKey = outputPath.resolve("jmh-result.json");
        logger.info("Saving verbatim JMH result JSON: {}", resultJsonKey);
        storageService.saveFile(resultJsonKey, machineReadableOutput);

        String environmentJsonKey = outputPath.resolve("environment.json").toString();
        // One timestamp per run, not one per result: the sort key already separates measurements
        // by class, method and mode, and a per-result clock read would make two results from the
        // same run differ by a stray millisecond.
        Instant createdAt = Instant.now();

        List<StoredMeasurement> measurements = new ArrayList<>();
        for (JmhResult jmhResult : getResultLoaderService().loadJmhResults(machineReadableOutput)) {
            logger.debug("JMH result: {}", jmhResult);
            measurements.add(JmhMeasurementMapper.toMeasurement(
                jmhResult,
                commonOptions.project(),
                commonOptions.requestId(),
                createdAt,
                commonOptions.tags(),
                outputPath.toString(),
                resultJsonKey.toString(),
                environmentJsonKey,
                profilerOutputPath.apply(jmhResult)));
        }
        return measurements;
    }
}
