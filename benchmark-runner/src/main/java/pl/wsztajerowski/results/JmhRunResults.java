package pl.wsztajerowski.results;

import pl.wsztajerowski.baas.model.JobLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.baas.model.StoredMeasurement;
import pl.wsztajerowski.entities.jmh.JmhResult;
import pl.wsztajerowski.options.CommonSharedOptions;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static pl.wsztajerowski.infra.ResultLoaderService.getResultLoaderService;

/**
 * Shared by the three JMH-flavoured services: maps every result in the JMH result JSON into the
 * stored shape. Each service uploads the JSON itself, at {@link #resultJsonKey}, before mapping.
 *
 * <p>The stored item is deliberately thin — {@code rawData} and {@code scorePercentiles} are
 * dropped — so the unmodified JMH JSON has to go somewhere retrievable. It goes to S3 under the
 * job's result path, and every measurement from that job points at it via {@code resultJsonKey}.
 *
 * <p>The upload happens before the store write, because a job that fails at the store must still
 * leave its artifacts behind.
 */
public final class JmhRunResults {
    private static final Logger logger = LoggerFactory.getLogger(JmhRunResults.class);

    private JmhRunResults() {}

    /** For runs with no profiler: every measurement gets a null profiler-output prefix. */
    public static final Function<JmhResult, String> NO_PROFILER_OUTPUT = result -> null;

    /** Where the verbatim result JSON goes, and what every measurement's {@code resultJsonKey} names. */
    public static Path resultJsonKey(CommonSharedOptions commonOptions) {
        return commonOptions.resultPath().resolve(JobLayout.RESULT_JSON_NAME);
    }

    public static List<StoredMeasurement> map(
        CommonSharedOptions commonOptions, Path machineReadableOutput,
        Function<JmhResult, String> profilerOutputPath) {

        Path outputPath = commonOptions.resultPath();
        Path resultJsonKey = resultJsonKey(commonOptions);
        String environmentJsonKey = outputPath.resolve(JobLayout.ENVIRONMENT_NAME).toString();
        // One timestamp per job, not one per result: the sort key already separates measurements
        // by class, method and mode, and a per-result clock read would make two results from the
        // same job differ by a stray millisecond. The instant comes from whoever named the job, so
        // the job identifier's timestamp and this one are the same value, not two nearby ones.
        Instant createdAt = commonOptions.createdAt();

        List<StoredMeasurement> measurements = new ArrayList<>();
        for (JmhResult jmhResult : getResultLoaderService().loadJmhResults(machineReadableOutput)) {
            logger.debug("JMH result: {}", jmhResult);
            measurements.add(JmhMeasurementMapper.toMeasurement(
                jmhResult,
                commonOptions.project(),
                commonOptions.jobId(),
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
