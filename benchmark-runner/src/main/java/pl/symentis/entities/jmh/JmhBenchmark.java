package pl.symentis.entities.jmh;

import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Id;

@Entity("jmh-benchmarks")
public record JmhBenchmark(
    @Id
    JmhBenchmarkId benchmarkId,
    JmhResult jmhResult,
    JmhResult jmhWithAsyncResult,
    BenchmarkMetadata benchmarkMetadata) {
}
