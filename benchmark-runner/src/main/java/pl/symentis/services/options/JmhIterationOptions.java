package pl.symentis.services.options;

public record JmhIterationOptions(
    Integer iterations,
    String minimumIterationTime,
    String iterationTimeout,
    Integer operationsPerInvocations,
    Boolean synchronizeIterations,
    Boolean forceGCBetweenIterations) {

    public static JmhIterationOptionsBuilder jmhIterationOptionsBuilder() {
        return new JmhIterationOptionsBuilder();
    }

    public static final class JmhIterationOptionsBuilder {
        private Integer iterations;
        private String minimumIterationTime;
        private String iterationTimeout;
        private Integer operationsPerInvocations;
        private Boolean synchronizeIterations;
        private Boolean forceGCBetweenIterations;

        private JmhIterationOptionsBuilder() {
        }


        public JmhIterationOptionsBuilder withIterations(Integer iterations) {
            this.iterations = iterations;
            return this;
        }

        public JmhIterationOptionsBuilder withMinimumIterationTime(String minimumIterationTime) {
            this.minimumIterationTime = minimumIterationTime;
            return this;
        }

        public JmhIterationOptionsBuilder withIterationTimeout(String iterationTimeout) {
            this.iterationTimeout = iterationTimeout;
            return this;
        }

        public JmhIterationOptionsBuilder withOperationsPerInvocations(Integer operationsPerInvocations) {
            this.operationsPerInvocations = operationsPerInvocations;
            return this;
        }

        public JmhIterationOptionsBuilder withSynchronizeIterations(Boolean synchronizeIterations) {
            this.synchronizeIterations = synchronizeIterations;
            return this;
        }

        public JmhIterationOptionsBuilder withForceGCBetweenIterations(Boolean forceGCBetweenIterations) {
            this.forceGCBetweenIterations = forceGCBetweenIterations;
            return this;
        }

        public JmhIterationOptions build() {
            return new JmhIterationOptions(iterations, minimumIterationTime, iterationTimeout, operationsPerInvocations, synchronizeIterations, forceGCBetweenIterations);
        }
    }
}
