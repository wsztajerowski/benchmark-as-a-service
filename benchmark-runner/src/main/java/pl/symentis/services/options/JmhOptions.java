package pl.symentis.services.options;

public record JmhOptions(
    JmhBenchmarkOptions benchmarkOptions,
    JmhOutputOptions outputOptions,
    JmhWarmupOptions warmupOptions,
    JmhIterationOptions iterationOptions,
    JmhJvmOptions jvmOptions) {
}
