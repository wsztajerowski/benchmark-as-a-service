
package pl.wsztajerowski.entities.jmh;

import dev.morphia.annotations.Entity;

import java.util.List;
import java.util.Map;

@Entity
public record JmhResult(
    String jmhVersion,
    String benchmark,
    String mode,
    Long threads,
    Long forks,
    String jvm,
    List<String> jvmArgs,
    String jdkVersion,
    String vmName,
    String vmVersion,
    Long warmupIterations,
    String warmupTime,
    Long warmupBatchSize,
    Long measurementIterations,
    String measurementTime,
    Long measurementBatchSize,
    Metric primaryMetric,
    Map<String, Metric> secondaryMetrics) {
}
