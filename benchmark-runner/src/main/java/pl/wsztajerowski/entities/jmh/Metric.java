
package pl.wsztajerowski.entities.jmh;

import dev.morphia.annotations.Entity;

import java.util.List;
import java.util.Map;

@Entity
public record Metric(
    Double score,
    Double scoreError,
    List<Double> scoreConfidence,
    Map<Double, Double> scorePercentiles,
    String scoreUnit,
    List<List<Double>> rawData) {
}
