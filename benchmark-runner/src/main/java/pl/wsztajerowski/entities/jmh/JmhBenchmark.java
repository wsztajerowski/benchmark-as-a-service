package pl.wsztajerowski.entities.jmh;

import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Id;

import java.time.LocalDateTime;
@Entity("jmh_benchmarks")
public record JmhBenchmark(
    @Id
    JmhBenchmarkId benchmarkId,
    JmhResult jmhResult,
    JmhResult jmhWithAsyncResult,
    BenchmarkMetadata benchmarkMetadata,
    LocalDateTime createdAt) {
}
