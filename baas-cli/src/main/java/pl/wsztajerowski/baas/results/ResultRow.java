package pl.wsztajerowski.baas.results;

public record ResultRow(
    String requestId,
    String benchmarkName,
    String benchmarkType,
    String mode,
    double score,
    double scoreError,
    String scoreUnit,
    String createdAt
) {}
