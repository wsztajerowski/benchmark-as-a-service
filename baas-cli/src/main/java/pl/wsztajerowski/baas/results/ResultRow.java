package pl.wsztajerowski.baas.results;

public record ResultRow(
    String requestId,
    String benchmarkName,
    String benchmarkType,
    String mode,
    double score,
    double scoreError,
    String scoreUnit,
    String createdAt,
    /**
     * From the free-form {@code benchmarkMetadata.tags}, so null for every run recorded before
     * the prebaked-image change. Tier 1 of the environment comparison: enough to see that two
     * rows sat on different environments, without fetching anything from S3.
     */
    String imageVersion,
    String instanceType
) {}
