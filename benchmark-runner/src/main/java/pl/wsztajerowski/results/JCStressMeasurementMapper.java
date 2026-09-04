package pl.wsztajerowski.results;

import pl.wsztajerowski.baas.model.JcstressSummary;
import pl.wsztajerowski.baas.model.MeasurementKind;
import pl.wsztajerowski.baas.model.StoredMeasurement;
import pl.wsztajerowski.entities.jcstress.JCStressResult;

import java.time.Instant;
import java.util.Map;

/**
 * One measurement per JCStress run, not per test. JCStress names only non-passing tests — passing
 * ones are counted, never named — so per-test items would cover failures only, and the full result
 * files are already in S3 under the run's result path.
 */
public final class JCStressMeasurementMapper {

    private JCStressMeasurementMapper() {}

    public static StoredMeasurement toMeasurement(
        JCStressResult result, String project, String requestId, Instant createdAt,
        Map<String, String> tags, String resultPath, String environmentJsonKey) {

        return new StoredMeasurement(
            project,
            requestId,
            createdAt,
            MeasurementKind.JCSTRESS,
            null, null, null, null, null, null,
            Map.of(),
            summaryOf(result),
            tags,
            resultPath,
            null,
            environmentJsonKey,
            null);
    }

    /**
     * {@code JCStressResult} carries only {@code totalTests} and {@code passedTests}; the failed
     * and error counts are the sizes of the named sets, which is exact because JCStress names
     * every non-passing test. {@code JcstressSummary} null-defaults its three maps, so a null
     * from {@code JCStressResult} is safe.
     */
    private static JcstressSummary summaryOf(JCStressResult result) {
        return new JcstressSummary(
            result.totalTests(),
            result.passedTests(),
            sizeOf(result.testsWithFailedResults()),
            sizeOf(result.testsWithErrorResults()),
            result.testsWithFailedResults(),
            result.testsWithErrorResults(),
            result.testsWithInterestingResults());
    }

    private static int sizeOf(Map<String, String> tests) {
        return tests == null ? 0 : tests.size();
    }
}
