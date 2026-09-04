package pl.wsztajerowski.baas.model;

/** JMH secondary metrics reduced to what a table view needs; the full form stays in the S3 result JSON. */
public record SecondaryMetric(double score, String unit) {}
