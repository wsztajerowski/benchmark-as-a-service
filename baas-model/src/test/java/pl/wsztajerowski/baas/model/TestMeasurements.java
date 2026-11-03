package pl.wsztajerowski.baas.model;

import java.time.Instant;
import java.util.Map;

/**
 * {@link StoredMeasurement}s for tests, by name rather than by position (T6). Shared with the CLI's
 * and the runner's tests through baas-model's test-jar.
 */
public final class TestMeasurements {

    private TestMeasurements() {
    }

    /**
     * A JMH throughput measurement of project {@code lynx-journal}: {@code com.example.Bench.method},
     * 1234.5 ± 1.0 ops/s, created 2026-08-19 09:00, keys under {@code main/jmh/ts}.
     */
    public static Builder jmh() {
        return new Builder(BenchmarkType.JMH)
            .benchmarkClass("com.example.Bench").benchmarkMethod("method").mode("thrpt")
            .score(1234.5).scoreError(1.0).scoreUnit("ops/s")
            .resultJsonKey("main/jmh/ts/jmh-result.json");
    }

    /** A JCStress measurement of project {@code lynx-journal}, with no score and no verdict unless set. */
    public static Builder jcstress() {
        return new Builder(BenchmarkType.JCSTRESS);
    }

    /** Every field of {@code measurement}, to change some. */
    public static Builder copy(StoredMeasurement m) {
        return new Builder(m.kind()).project(m.project()).jobId(m.jobId()).createdAt(m.createdAt())
            .benchmarkClass(m.benchmarkClass()).benchmarkMethod(m.benchmarkMethod()).mode(m.mode()).params(m.params())
            .score(m.score()).scoreError(m.scoreError()).scoreUnit(m.scoreUnit()).secondaryMetrics(m.secondaryMetrics())
            .jcstress(m.jcstress()).tags(m.tags()).resultPath(m.resultPath()).resultJsonKey(m.resultJsonKey())
            .environmentJsonKey(m.environmentJsonKey());
    }

    public static final class Builder {
        private final BenchmarkType kind;
        private String project = "lynx-journal";
        private String jobId = "req-1";
        private Instant createdAt = Instant.parse("2026-08-19T09:00:00.000Z");
        private String benchmarkClass;
        private String benchmarkMethod;
        private String mode;
        private Map<String, String> params = Map.of();
        private Double score;
        private Double scoreError;
        private String scoreUnit;
        private Map<String, SecondaryMetric> secondaryMetrics = Map.of();
        private JcstressSummary jcstress;
        private Map<String, String> tags = Map.of();
        private String resultPath = "main/jmh/ts";
        private String resultJsonKey;
        private String environmentJsonKey = "main/jmh/ts/environment.json";

        private Builder(BenchmarkType kind) {
            this.kind = kind;
        }

        public Builder project(String project) { this.project = project; return this; }
        public Builder jobId(String jobId) { this.jobId = jobId; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }
        public Builder createdAt(String createdAt) { return createdAt(Instant.parse(createdAt)); }
        public Builder benchmarkClass(String benchmarkClass) { this.benchmarkClass = benchmarkClass; return this; }
        public Builder benchmarkMethod(String benchmarkMethod) { this.benchmarkMethod = benchmarkMethod; return this; }
        public Builder mode(String mode) { this.mode = mode; return this; }
        public Builder params(Map<String, String> params) { this.params = params; return this; }
        public Builder score(Double score) { this.score = score; return this; }
        public Builder scoreError(Double scoreError) { this.scoreError = scoreError; return this; }
        public Builder scoreUnit(String scoreUnit) { this.scoreUnit = scoreUnit; return this; }
        public Builder secondaryMetrics(Map<String, SecondaryMetric> metrics) { this.secondaryMetrics = metrics; return this; }
        public Builder jcstress(JcstressSummary jcstress) { this.jcstress = jcstress; return this; }
        public Builder tags(Map<String, String> tags) { this.tags = tags; return this; }
        public Builder resultPath(String resultPath) { this.resultPath = resultPath; return this; }
        public Builder resultJsonKey(String resultJsonKey) { this.resultJsonKey = resultJsonKey; return this; }
        public Builder environmentJsonKey(String key) { this.environmentJsonKey = key; return this; }

        public StoredMeasurement build() {
            return new StoredMeasurement(project, jobId, createdAt, kind, benchmarkClass, benchmarkMethod, mode,
                params, score, scoreError, scoreUnit, secondaryMetrics, jcstress, tags,
                resultPath, resultJsonKey, environmentJsonKey);
        }
    }
}
