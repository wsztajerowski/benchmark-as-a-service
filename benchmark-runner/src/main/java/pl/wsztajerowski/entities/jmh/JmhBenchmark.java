package pl.wsztajerowski.entities.jmh;

import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Id;
@Entity("jmh_benchmarks")
public record JmhBenchmark(
    @Id
    JmhBenchmarkId benchmarkId,
    JmhResult jmhResult,
    BenchmarkMetadata benchmarkMetadata) {
}
