package pl.wsztajerowski.baas.model;

import java.util.Map;

/**
 * JCStress reports counts for everything and names only non-passing tests, so per-test items would
 * cover failures alone — hence one summary on one item, with the full result files in S3.
 */
public record JcstressSummary(
    int totalTests,
    int passedTests,
    int failedTests,
    int errorTests,
    Map<String, String> failed,
    Map<String, String> errors,
    Map<String, String> interesting
) {
    public JcstressSummary {
        failed = Map.copyOf(failed);
        errors = Map.copyOf(errors);
        interesting = Map.copyOf(interesting);
    }
}
