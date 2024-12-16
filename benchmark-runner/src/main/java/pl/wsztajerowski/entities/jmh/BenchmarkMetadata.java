package pl.wsztajerowski.entities.jmh;

import dev.morphia.annotations.Entity;

import java.util.Map;

@Entity
public record BenchmarkMetadata(Map<String, String> profilerOutputPaths) {
}
