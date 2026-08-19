package pl.wsztajerowski.results;

import pl.wsztajerowski.baas.model.MeasurementKind;
import pl.wsztajerowski.baas.model.SecondaryMetric;
import pl.wsztajerowski.baas.model.StoredMeasurement;
import pl.wsztajerowski.entities.jmh.JmhResult;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Maps JMH's parsing type into the stored shape. {@code rawData} and {@code scorePercentiles} are
 * deliberately dropped — they dominate a JMH result's size and DynamoDB caps an item at 400 KB, so
 * full fidelity lives in the verbatim result JSON in S3 and {@code resultJsonKey} points at it.
 */
public final class JmhMeasurementMapper {

    private JmhMeasurementMapper() {}

    public static StoredMeasurement toMeasurement(
        JmhResult result, String project, String requestId, Instant createdAt,
        Map<String, String> tags, String resultPath, String resultJsonKey, String environmentJsonKey,
        String profilerOutputPath) {

        String fullyQualified = result.benchmark();
        int lastDot = fullyQualified.lastIndexOf('.');
        if (lastDot < 0) {
            throw new IllegalArgumentException(
                "Benchmark name has no class/method separator: " + fullyQualified);
        }

        var primary = result.primaryMetric();
        return new StoredMeasurement(
            project,
            requestId,
            createdAt,
            MeasurementKind.JMH,
            fullyQualified.substring(0, lastDot),
            fullyQualified.substring(lastDot + 1),
            result.mode(),
            primary == null ? null : primary.score(),
            primary == null ? null : primary.scoreError(),
            primary == null ? null : primary.scoreUnit(),
            secondaryMetrics(result),
            null,
            tags,
            resultPath,
            resultJsonKey,
            environmentJsonKey,
            profilerOutputPath);
    }

    /**
     * {@code SecondaryMetric.score} is a primitive, so a JMH metric carrying a null score would
     * NPE on unboxing and take the whole run's write down with it. Such an entry is dropped
     * instead. Non-finite scores are NOT filtered here — {@code MeasurementItemMapper} already
     * drops those on the way into DynamoDB, and duplicating that would hide the case from the
     * test which covers it.
     */
    private static Map<String, SecondaryMetric> secondaryMetrics(JmhResult result) {
        if (result.secondaryMetrics() == null) {
            return Map.of();
        }
        return result.secondaryMetrics().entrySet().stream()
            .filter(entry -> entry.getValue() != null && entry.getValue().score() != null)
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> new SecondaryMetric(entry.getValue().score(), entry.getValue().scoreUnit())));
    }
}
