package pl.wsztajerowski.baas.results;

import pl.wsztajerowski.baas.model.StoredMeasurement;
import pl.wsztajerowski.baas.model.TagKeys;

import java.util.Map;

/**
 * One row as the CLI presents it. Flattened from {@link StoredMeasurement} because the display
 * columns are stable while the stored shape is not, and because grouping and filtering both need
 * the whole tag map rather than a fixed set of promoted fields.
 */
public record ResultRow(
    String requestId,
    String benchmarkName,
    String benchmarkType,
    String mode,
    double score,
    double scoreError,
    String scoreUnit,
    String createdAt,
    Map<String, String> tags
) {

    public ResultRow {
        tags = tags == null ? Map.of() : Map.copyOf(tags);
    }

    /**
     * An absent score or score error becomes NaN, never 0. The store leaves non-finite values out
     * of the item, and JMH reports a NaN error for any run under three iterations; reading that
     * back as 0 renders {@code ±0.000}, a claim of perfect precision. Each renderer turns NaN into
     * its own "unknown".
     */
    public static ResultRow from(StoredMeasurement measurement) {
        return new ResultRow(
            measurement.requestId(),
            benchmarkNameOf(measurement),
            measurement.tags().getOrDefault(TagKeys.TYPE, ""),
            measurement.mode(),
            measurement.score() == null ? Double.NaN : measurement.score(),
            measurement.scoreError() == null ? Double.NaN : measurement.scoreError(),
            measurement.scoreUnit() == null ? "" : measurement.scoreUnit(),
            measurement.createdAt() == null ? "" : measurement.createdAt().toString(),
            measurement.tags());
    }

    /**
     * The model keeps class and method apart because the sort key is built from both; the display
     * wants the fully qualified name JMH itself reports. JCStress has neither, and its single
     * summary row is named for the run.
     */
    private static String benchmarkNameOf(StoredMeasurement measurement) {
        if (measurement.benchmarkClass() == null) {
            return "(jcstress) " + measurement.requestId();
        }
        return measurement.benchmarkClass() + "." + measurement.benchmarkMethod();
    }

    public String tag(String key) {
        return tags.get(key);
    }

    /**
     * Tier 1 of the environment comparison, read from the free-form tag map, so null for every run
     * recorded before the prebaked-image change. Enough to see that two rows sat on different
     * environments without fetching anything from S3.
     */
    public String imageVersion() {
        return tags.get(TagKeys.IMAGE_VERSION);
    }

    public String instanceType() {
        return tags.get(TagKeys.INSTANCE_TYPE);
    }
}
