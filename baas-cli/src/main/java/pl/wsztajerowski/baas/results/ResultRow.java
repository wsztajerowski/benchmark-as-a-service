package pl.wsztajerowski.baas.results;

import pl.wsztajerowski.baas.model.JcstressSummary;
import pl.wsztajerowski.baas.model.MeasurementKind;
import pl.wsztajerowski.baas.model.ResultKeys;
import pl.wsztajerowski.baas.model.SecondaryMetric;
import pl.wsztajerowski.baas.model.StoredMeasurement;
import pl.wsztajerowski.baas.model.TagKeys;

import java.util.Map;

/**
 * One row as the CLI presents it. Flattened from {@link StoredMeasurement} because the display
 * columns are stable while the stored shape is not, and because grouping and filtering both need
 * the whole tag map rather than a fixed set of promoted fields.
 */
public record ResultRow(
    String jobId,
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
    Map<String, String> params,
    /**
     * A JCStress job's verdict; null for JMH. JCStress has no score, and its job ends
     * {@code completed} whatever its tests found — this is where a failure shows.
     */
    JcstressSummary jcstress,
    /** JMH's secondary metrics ({@code -prof gc}'s {@code gc.alloc.rate.norm}, …); empty otherwise. */
    Map<String, SecondaryMetric> secondaryMetrics,
    /**
     * The library that produced the measurement, as stored — required on every item, unlike the
     * free-form {@code type} tag. {@code results diff} refuses to compare across it.
     */
    MeasurementKind kind
) {

    public ResultRow {
        tags = tags == null ? Map.of() : Map.copyOf(tags);
        params = params == null ? Map.of() : Map.copyOf(params);
        secondaryMetrics = secondaryMetrics == null ? Map.of() : Map.copyOf(secondaryMetrics);
        if (kind == null) {
            throw new IllegalArgumentException("kind is required");
        }
    }

    /** Without a stored kind: a row built by hand, mostly in tests, whose verdict says which library. */
    public ResultRow(String jobId, String benchmarkName, String benchmarkType, String mode,
                     double score, double scoreError, String scoreUnit, String createdAt,
                     Map<String, String> tags, String project, Map<String, String> params,
                     JcstressSummary jcstress, Map<String, SecondaryMetric> secondaryMetrics) {
        this(jobId, benchmarkName, benchmarkType, mode, score, scoreError, scoreUnit, createdAt,
            tags, project, params, jcstress, secondaryMetrics,
            jcstress != null ? MeasurementKind.JCSTRESS : MeasurementKind.JMH);
    }

    /** Without a verdict or secondary metrics: a JMH row built by hand, mostly in tests. */
    public ResultRow(String jobId, String benchmarkName, String benchmarkType, String mode,
                     double score, double scoreError, String scoreUnit, String createdAt,
                     Map<String, String> tags, String project, Map<String, String> params) {
        this(jobId, benchmarkName, benchmarkType, mode, score, scoreError, scoreUnit, createdAt,
            tags, project, params, null, Map.of());
    }

    /** Without params: every benchmark that declares no {@code @Param}. */
    public ResultRow(String jobId, String benchmarkName, String benchmarkType, String mode,
                     double score, double scoreError, String scoreUnit, String createdAt,
                     Map<String, String> tags, String project) {
        this(jobId, benchmarkName, benchmarkType, mode, score, scoreError, scoreUnit, createdAt,
            tags, project, Map.of());
    }

    /**
     * Without the stored partition's project: falls back to the {@code project} tag, which every
     * measurement the runner wrote carries. For rows built by hand, mostly in tests.
     */
    public ResultRow(String jobId, String benchmarkName, String benchmarkType, String mode,
                     double score, double scoreError, String scoreUnit, String createdAt,
                     Map<String, String> tags) {
        this(jobId, benchmarkName, benchmarkType, mode, score, scoreError, scoreUnit, createdAt,
            tags, tags == null ? null : tags.get(TagKeys.PROJECT));
    }

    public static ResultRow from(StoredMeasurement measurement) {
        return new ResultRow(
            measurement.jobId(),
            benchmarkNameOf(measurement),
            measurement.tags().getOrDefault(TagKeys.TYPE, ""),
            measurement.mode(),
            // Absent means unknown, never zero: the store writes a NaN as absent, and a zero read
            // back won every lower-is-better --best-per group and printed as a measurement.
            measurement.score() == null ? Double.NaN : measurement.score(),
            measurement.scoreError() == null ? Double.NaN : measurement.scoreError(),
            measurement.scoreUnit() == null ? "" : measurement.scoreUnit(),
            measurement.createdAt() == null ? "" : measurement.createdAt().toString(),
            measurement.tags(),
            measurement.project(),
            measurement.params(),
            measurement.jcstress(),
            measurement.secondaryMetrics(),
            measurement.kind());
    }

    /** Some of a JCStress job's tests failed or errored. */
    public boolean testsFailed() {
        return jcstress != null && jcstress.failedTests() + jcstress.errorTests() > 0;
    }

    /**
     * The model keeps class and method apart because the sort key is built from both; the display
     * wants the fully qualified name JMH itself reports. JCStress has neither, and its single
     * summary row is named for the job.
     */
    private static String benchmarkNameOf(StoredMeasurement measurement) {
        if (measurement.benchmarkClass() == null) {
            return "(jcstress) " + measurement.jobId();
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

    /** Tagged {@code hidden=true}: hidden from sweeps, shown faint under {@code --show-hidden}. */
    public boolean hidden() {
        return ResultsQueryService.HIDDEN_VALUE.equals(tags.get(ResultsQueryService.HIDDEN));
    }

    /**
     * Read from the free-form tag map, so null for every job recorded before the prebaked-image
     * change. Flat in the JSON output, where CI and other consumers read them.
     */
    public String imageVersion() {
        return tags.get(TagKeys.IMAGE_VERSION);
    }

    public String instanceType() {
        return tags.get(TagKeys.INSTANCE_TYPE);
    }
}
