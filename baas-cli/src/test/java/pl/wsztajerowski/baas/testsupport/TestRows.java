package pl.wsztajerowski.baas.testsupport;

import pl.wsztajerowski.baas.model.BenchmarkType;
import pl.wsztajerowski.baas.model.JcstressSummary;
import pl.wsztajerowski.baas.model.SecondaryMetric;
import pl.wsztajerowski.baas.model.TagKeys;
import pl.wsztajerowski.baas.results.ResultRow;

import java.util.Map;

/**
 * {@link ResultRow}s for tests, by name rather than by position (T6): a test sets the columns it is
 * about and every other one keeps the default here.
 */
public final class TestRows {

    private TestRows() {
    }

    /**
     * {@code com.example.Bench.method}, jmh thrpt, 1.0 ± 0.1 ops/s, job {@code job-1}; its project is
     * the {@code project} tag unless set, as for a row read from a measurement without a partition.
     */
    public static Builder row() {
        return new Builder();
    }

    public static final class Builder {
        private String jobId = "job-1";
        private String benchmarkName = "com.example.Bench.method";
        private String benchmarkType = "jmh";
        private String mode = "thrpt";
        private double score = 1.0;
        private double scoreError = 0.1;
        private String scoreUnit = "ops/s";
        private String createdAt = "2026-08-19T09:00:00.000Z";
        private Map<String, String> tags = Map.of();
        private String project;
        private boolean projectSet;
        private Map<String, String> params = Map.of();
        private JcstressSummary jcstress;
        private Map<String, SecondaryMetric> secondaryMetrics = Map.of();
        private BenchmarkType kind;

        private Builder() {
        }

        public Builder jobId(String jobId) { this.jobId = jobId; return this; }
        public Builder benchmarkName(String benchmarkName) { this.benchmarkName = benchmarkName; return this; }
        public Builder benchmarkType(String benchmarkType) { this.benchmarkType = benchmarkType; return this; }
        public Builder mode(String mode) { this.mode = mode; return this; }
        public Builder score(double score) { this.score = score; return this; }
        public Builder scoreError(double scoreError) { this.scoreError = scoreError; return this; }
        public Builder scoreUnit(String scoreUnit) { this.scoreUnit = scoreUnit; return this; }
        public Builder createdAt(String createdAt) { this.createdAt = createdAt; return this; }
        public Builder tags(Map<String, String> tags) { this.tags = tags; return this; }
        public Builder project(String project) { this.project = project; projectSet = true; return this; }
        public Builder params(Map<String, String> params) { this.params = params; return this; }
        public Builder jcstress(JcstressSummary jcstress) { this.jcstress = jcstress; return this; }
        public Builder secondaryMetrics(Map<String, SecondaryMetric> metrics) { this.secondaryMetrics = metrics; return this; }
        /** JCSTRESS when a verdict is set, JMH otherwise, unless set. */
        public Builder kind(BenchmarkType kind) { this.kind = kind; return this; }

        public ResultRow build() {
            return new ResultRow(jobId, benchmarkName, benchmarkType, mode, score, scoreError, scoreUnit, createdAt,
                tags, projectSet ? project : tags == null ? null : tags.get(TagKeys.PROJECT), params, jcstress,
                secondaryMetrics,
                kind != null ? kind : jcstress != null ? BenchmarkType.JCSTRESS : BenchmarkType.JMH);
        }
    }
}
