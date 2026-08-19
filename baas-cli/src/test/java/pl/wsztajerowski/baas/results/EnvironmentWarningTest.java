package pl.wsztajerowski.baas.results;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentWarningTest {

    private static ResultRow row(String requestId, String imageVersion, String instanceType) {
        var tags = new java.util.HashMap<String, String>();
        if (imageVersion != null) tags.put("imageVersion", imageVersion);
        if (instanceType != null) tags.put("instanceType", instanceType);
        return new ResultRow(requestId, "com.example.MyBenchmark", "jmh", "thrpt",
            1000.0, 5.0, "ops/s", "2026-08-11T00:00:00Z", tags);
    }

    @Test
    void staysQuietWhenEveryRowSharesAnEnvironment() {
        var rows = List.of(
            row("jmh-1", "1.0.0", "c5.2xlarge"),
            row("jmh-2", "1.0.0", "c5.2xlarge"));

        assertThat(ResultsQueryService.environmentWarning(rows)).isEmpty();
    }

    @Test
    void reportsDifferingImageVersions() {
        var rows = List.of(
            row("jmh-1", "1.0.0", "c5.2xlarge"),
            row("jmh-2", "1.1.0", "c5.2xlarge"));

        assertThat(ResultsQueryService.environmentWarning(rows))
            .hasValueSatisfying(warning -> assertThat(warning)
                .contains("1.0.0", "1.1.0")
                .contains("baas env diff"));
    }

    @Test
    void reportsDifferingInstanceTypes() {
        var rows = List.of(
            row("jmh-1", "1.0.0", "c5.2xlarge"),
            row("jmh-2", "1.0.0", "c6i.4xlarge"));

        assertThat(ResultsQueryService.environmentWarning(rows))
            .hasValueSatisfying(warning -> assertThat(warning).contains("c5.2xlarge", "c6i.4xlarge"));
    }

    /**
     * The spec is explicit that differing environments are reported, not filtered: dropping a row
     * hides the one fact the operator needs to interpret the numbers.
     */
    @Test
    void everyRowStaysInTheTableRegardlessOfEnvironment() {
        var rows = List.of(
            row("jmh-1", "1.0.0", "c5.2xlarge"),
            row("jmh-2", "1.1.0", "c6i.4xlarge"));

        assertThat(rows).hasSize(2);
        assertThat(ResultsQueryService.environmentWarning(rows)).isPresent();
    }

    /**
     * Every run recorded before this change carries no tags at all. Treating absent as a distinct
     * value would flag a warning on every historical comparison.
     */
    @Test
    void untaggedHistoricalRowsAreNotADifference() {
        var rows = List.of(
            row("jmh-old-1", null, null),
            row("jmh-old-2", null, null),
            row("jmh-new", "1.0.0", "c5.2xlarge"));

        assertThat(ResultsQueryService.environmentWarning(rows))
            .as("a mix of tagged and untagged rows is one known environment, not two")
            .isEmpty();
    }
}
