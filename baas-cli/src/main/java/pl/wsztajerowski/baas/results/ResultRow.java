package pl.wsztajerowski.baas.results;

import pl.wsztajerowski.baas.model.ResultKeys;
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
    Map<String, String> tags,
    String project,
    /** A {@code @Param} sweep's variant; empty otherwise. Part of the benchmark's identity, not a tag. */
    Map<String, String> params
) {

    public ResultRow {
        tags = tags == null ? Map.of() : Map.copyOf(tags);
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** Without params: every benchmark that declares no {@code @Param}. */
    public ResultRow(String requestId, String benchmarkName, String benchmarkType, String mode,
                     double score, double scoreError, String scoreUnit, String createdAt,
                     Map<String, String> tags, String project) {
        this(requestId, benchmarkName, benchmarkType, mode, score, scoreError, scoreUnit, createdAt,
            tags, project, Map.of());
    }

    /**
     * Without the stored partition's project: falls back to the {@code project} tag, which every
     * measurement the runner wrote carries. For rows built by hand, mostly in tests.
     */
    public ResultRow(String requestId, String benchmarkName, String benchmarkType, String mode,
                     double score, double scoreError, String scoreUnit, String createdAt,
                     Map<String, String> tags) {
        this(requestId, benchmarkName, benchmarkType, mode, score, scoreError, scoreUnit, createdAt,
            tags, tags == null ? null : tags.get(TagKeys.PROJECT));
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
            measurement.tags(),
            measurement.project(),
            measurement.params());
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

    /** The canonical {@code k=v,k=v} text the sort key uses — one form for grouping and ordering too. */
    public String paramsKey() {
        return ResultKeys.formatParams(params);
    }

    public String tag(String key) {
        return tags.get(key);
    }

    /** Tagged {@code exclude_from_results=true}: hidden from sweeps, shown faint under {@code --all-runs}. */
    public boolean excluded() {
        return ResultsQueryService.EXCLUDED_VALUE.equals(tags.get(ResultsQueryService.EXCLUDE_FROM_RESULTS));
    }

    /**
     * Read from the free-form tag map, so null for every run recorded before the prebaked-image
     * change. Flat in the JSON output, where CI and other consumers read them.
     */
    public String imageVersion() {
        return tags.get(TagKeys.IMAGE_VERSION);
    }

    public String instanceType() {
        return tags.get(TagKeys.INSTANCE_TYPE);
    }
}
