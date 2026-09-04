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

    public static ResultRow from(StoredMeasurement measurement) {
        return new ResultRow(
            measurement.requestId(),
            benchmarkNameOf(measurement),
            measurement.tags().getOrDefault(TagKeys.TYPE, ""),
            measurement.mode(),
            measurement.score() == null ? 0 : measurement.score(),
            measurement.scoreError() == null ? 0 : measurement.scoreError(),
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
