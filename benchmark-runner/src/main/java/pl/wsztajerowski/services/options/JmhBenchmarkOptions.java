package pl.wsztajerowski.services.options;

import java.nio.file.Path;

public record JmhBenchmarkOptions(
    Path benchmarkPath,
    Integer batchSize,
    Integer threads,
    String benchmarkMode,
    Boolean failAfterUnrecoverableError,
    String verbosityMode,
    Integer forks,
    String threadGroups,
    String timeUnit,
    String excludeBenchmarkRegex,
    String testNameRegex) {

    public static JmhBenchmarkOptionsBuilder jmhBenchmarkOptionsBuilder() {
        return new JmhBenchmarkOptionsBuilder();
    }

    public static final class JmhBenchmarkOptionsBuilder {
        private Path benchmarkPath;
        private Integer batchSize;
        private Integer threads;
        private String benchmarkMode;
        private Boolean failAfterUnrecoverableError;
        private String verbosityMode;
        private Integer forks;
        private String threadGroups;
        private String timeUnit;
        private String excludeBenchmarkRegex;
        private String testNameRegex;

        private JmhBenchmarkOptionsBuilder() {
        }

        public JmhBenchmarkOptionsBuilder withBenchmarkPath(Path benchmarkPath) {
            this.benchmarkPath = benchmarkPath;
            return this;
        }

        public JmhBenchmarkOptionsBuilder withBatchSize(Integer batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public JmhBenchmarkOptionsBuilder withThreads(Integer threads) {
            this.threads = threads;
            return this;
        }

        public JmhBenchmarkOptionsBuilder withBenchmarkMode(String benchmarkMode) {
            this.benchmarkMode = benchmarkMode;
            return this;
        }

        public JmhBenchmarkOptionsBuilder withFailAfterUnrecoverableError(Boolean failAfterUnrecoverableError) {
            this.failAfterUnrecoverableError = failAfterUnrecoverableError;
            return this;
        }

        public JmhBenchmarkOptionsBuilder withVerbosityMode(String verbosityMode) {
            this.verbosityMode = verbosityMode;
            return this;
        }

        public JmhBenchmarkOptionsBuilder withForks(Integer forks) {
            this.forks = forks;
            return this;
        }

        public JmhBenchmarkOptionsBuilder withThreadGroups(String threadGroups) {
            this.threadGroups = threadGroups;
            return this;
        }

        public JmhBenchmarkOptionsBuilder withTimeUnit(String timeUnit) {
            this.timeUnit = timeUnit;
            return this;
        }

        public JmhBenchmarkOptionsBuilder withExcludeBenchmarkRegex(String excludeBenchmarkRegex) {
            this.excludeBenchmarkRegex = excludeBenchmarkRegex;
            return this;
        }

        public JmhBenchmarkOptionsBuilder withTestNameRegex(String testNameRegex) {
            this.testNameRegex = testNameRegex;
            return this;
        }

        public JmhBenchmarkOptions build() {
            return new JmhBenchmarkOptions(benchmarkPath, batchSize, threads, benchmarkMode, failAfterUnrecoverableError, verbosityMode, forks, threadGroups, timeUnit, excludeBenchmarkRegex, testNameRegex);
        }
    }
}
