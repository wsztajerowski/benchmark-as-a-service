package pl.wsztajerowski.baas.model;

import java.time.Instant;
import java.util.Map;

/**
 * One stored measurement — one DynamoDB item, one Mongo document. The port speaks this shape;
 * each adapter owns its own physical layout.
 *
 * <p>Full-fidelity data (rawData, scorePercentiles, logs, profiling artifacts) is NOT here. It
 * lives in S3 under {@code resultPath}, reachable via {@code resultJsonKey}.
 */
public record StoredMeasurement(
    String project,
    String requestId,
    Instant createdAt,
    MeasurementKind kind,
    String benchmarkClass,
    String benchmarkMethod,
    String mode,
    Double score,
    Double scoreError,
    String scoreUnit,
    Map<String, SecondaryMetric> secondaryMetrics,
    JcstressSummary jcstress,
    Map<String, String> tags,
    String resultPath,
    String resultJsonKey,
    String environmentJsonKey,
    /**
     * S3 prefix holding this measurement's profiling artifacts, or null when the run produced
     * none. A prefix rather than the previous name-to-key map: listing it yields every artifact,
     * the field stays one bounded string however many the profiler emits, and callers do not have
     * to re-derive JMH's {@code -Throughput}-style directory suffix, which is exactly the kind of
     * hand-encoded key that returns nothing instead of failing to compile.
     */
    String profilerOutputPath
) {
    public StoredMeasurement {
        require(project, "project");
        require(requestId, "requestId");
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
        // Truncated because the sort-key format carries exactly three fractional digits; keeping
        // sub-millisecond precision here would make fromItem(toItem(m)) unequal to m for any
        // clock with finer resolution than the stored form.
        createdAt = createdAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        if (kind == null) throw new IllegalArgumentException("kind is required");
        if (kind == MeasurementKind.JMH) {
            require(benchmarkClass, "benchmarkClass");
            require(benchmarkMethod, "benchmarkMethod");
        }
        secondaryMetrics = secondaryMetrics == null ? Map.of() : Map.copyOf(secondaryMetrics);
        tags = tags == null ? Map.of() : Map.copyOf(tags);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    public StoredMeasurement withTags(Map<String, String> newTags) {
        return new StoredMeasurement(project, requestId, createdAt, kind, benchmarkClass,
            benchmarkMethod, mode, score, scoreError, scoreUnit, secondaryMetrics, jcstress,
            newTags, resultPath, resultJsonKey, environmentJsonKey, profilerOutputPath);
    }

    public StoredMeasurement withBenchmarkClass(String newBenchmarkClass) {
        return new StoredMeasurement(project, requestId, createdAt, kind, newBenchmarkClass,
            benchmarkMethod, mode, score, scoreError, scoreUnit, secondaryMetrics, jcstress,
            tags, resultPath, resultJsonKey, environmentJsonKey, profilerOutputPath);
    }
}
