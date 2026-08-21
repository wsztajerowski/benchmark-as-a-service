package pl.wsztajerowski.commands;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import pl.wsztajerowski.baas.model.RunId;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiCommonSharedOptionsTest {

    @Test
    void parsesTheResultsTableAndProject() {
        var options = parse("--results-table", "baas-abc-results", "--project", "lynx-journal");

        assertThat(options.getResultsTableName()).isEqualTo("baas-abc-results");
        assertThat(options.getProject()).isEqualTo("lynx-journal");
    }

    @Test
    void parsesNoDatabase() {
        assertThat(parse("--no-database", "--project", "p").isNoDatabase()).isTrue();
    }

    @Test
    void theMongoConnectionStringOptionSurvivesForStandaloneUse() {
        var options = parse("--mongo-connection-string", "mongodb://h:27017/db", "--project", "p");

        assertThat(options.getMongoConnectionString().toString()).contains("27017");
    }

    @Test
    void fallsBackToTheProjectTagSoBaasRunKeepsWorking() {
        assertThat(parse("--tag", "project=lynx-journal").getProject()).isEqualTo("lynx-journal");
    }

    @Test
    void anUnresolvedProjectIsRejectedRatherThanDefaulted() {
        assertThatThrownBy(() -> parse("--results-table", "t").getProject())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("--project");
    }

    @Test
    void createdAtIsTheCallerSuppliedInstant() {
        assertThat(parse("--created-at", "2026-08-20T17:44:32.812Z", "--project", "p").getCreatedAt())
            .isEqualTo(Instant.parse("2026-08-20T17:44:32.812Z"));
    }

    @Test
    void createdAtFallsBackToNowSoDirectInvocationKeepsWorking() {
        Instant before = Instant.now();
        Instant resolved = parse("--project", "p").getCreatedAt();
        assertThat(resolved).isBetween(before, Instant.now());
    }

    @Test
    void createdAtIsStableAcrossCallsWithinOneRun() {
        var options = parse("--project", "p");
        assertThat(options.getCreatedAt()).isEqualTo(options.getCreatedAt());
    }

    @Test
    void theDefaultRequestIdIsARunIdMintedFromTheRunsInstant() {
        var options = parse("--project", "p", "--created-at", "2026-08-20T17:44:32.812Z");

        assertThat(options.getRequestOptions().requestId())
            .hasSize(RunId.LENGTH)
            .startsWith("20260820T174432812Z-");
    }

    @Test
    void theDefaultResultPathIsTheUnifiedRunPrefix() {
        var options = parse("--project", "lynx-journal", "--created-at", "2026-08-20T17:44:32.812Z");
        var requestOptions = options.getRequestOptions();

        assertThat(requestOptions.resultPath().toString())
            .isEqualTo("runs/lynx-journal/" + requestOptions.requestId());
    }

    @Test
    void anExplicitResultPathStaysAnOverride() {
        var options = parse("--project", "p", "--result-path", "legacy/jmh/20260101_101010");

        assertThat(options.getRequestOptions().resultPath().toString())
            .isEqualTo("legacy/jmh/20260101_101010");
    }

    @Test
    void theRunsInstantReachesTheSharedOptions() {
        var options = parse("--project", "p", "--created-at", "2026-08-20T17:44:32.812Z");

        assertThat(options.getRequestOptions().createdAt())
            .isEqualTo(Instant.parse("2026-08-20T17:44:32.812Z"));
    }

    @Test
    void anExplicitProjectWinsOverTheTag() {
        var options = parse("--tag", "project=from-tag", "--project", "explicit");

        assertThat(options.getProject()).isEqualTo("explicit");
    }

    private static ApiCommonSharedOptions parse(String... args) {
        var options = new ApiCommonSharedOptions();
        new CommandLine(options).parseArgs(args);
        return options;
    }
}
