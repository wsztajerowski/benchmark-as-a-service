
package pl.wsztajerowski.entities.jmh;


import java.util.List;
import java.util.Map;

public record Metric(
    Double score,
    Double scoreError,
    List<Double> scoreConfidence,
    Map<Double, Double> scorePercentiles,
    String scoreUnit,
    List<List<Double>> rawData) {
}
