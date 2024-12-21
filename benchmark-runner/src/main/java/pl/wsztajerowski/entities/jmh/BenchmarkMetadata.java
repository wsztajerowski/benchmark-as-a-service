package pl.wsztajerowski.entities.jmh;

import dev.morphia.annotations.Entity;

import java.time.LocalDateTime;
import java.util.Map;

@Entity
public record BenchmarkMetadata(Map<String, String> tags, LocalDateTime createdAt, Map<String, String> profilerOutputPaths) {
}
