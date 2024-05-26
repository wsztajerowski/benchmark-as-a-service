package pl.wsztajerowski.services.options;

public record JmhWarmupOptions(
    Integer warmupIterations,
    Integer warmupBatchSize,
    String minimumWarmupIterationTime,
    Integer warmupForks,
    String warmupMode,
    String warmupBenchmarks) {

    public static JmhWarmupOptionsBuilder jmhWarmupOptionsBuilder() {
        return new JmhWarmupOptionsBuilder();
    }

    public static final class JmhWarmupOptionsBuilder {
        private Integer warmupIterations;
        private Integer warmupBatchSize;
        private String minimumWarmupIterationTime;
        private Integer warmupForks;
        private String warmupMode;
        private String warmupBenchmarks;

        private JmhWarmupOptionsBuilder() {
        }

        public JmhWarmupOptionsBuilder withWarmupIterations(Integer warmupIterations) {
            this.warmupIterations = warmupIterations;
            return this;
        }

        public JmhWarmupOptionsBuilder withWarmupBatchSize(Integer warmupBatchSize) {
            this.warmupBatchSize = warmupBatchSize;
            return this;
        }

        public JmhWarmupOptionsBuilder withMinimumWarmupIterationTime(String minimumWarmupIterationTime) {
            this.minimumWarmupIterationTime = minimumWarmupIterationTime;
            return this;
        }

        public JmhWarmupOptionsBuilder withWarmupForks(Integer warmupForks) {
            this.warmupForks = warmupForks;
            return this;
        }

        public JmhWarmupOptionsBuilder withWarmupMode(String warmupMode) {
            this.warmupMode = warmupMode;
            return this;
        }

        public JmhWarmupOptionsBuilder withWarmupBenchmarks(String warmupBenchmarks) {
            this.warmupBenchmarks = warmupBenchmarks;
            return this;
        }

        public JmhWarmupOptions build() {
            return new JmhWarmupOptions(warmupIterations, warmupBatchSize, minimumWarmupIterationTime, warmupForks, warmupMode, warmupBenchmarks);
        }
    }
}
