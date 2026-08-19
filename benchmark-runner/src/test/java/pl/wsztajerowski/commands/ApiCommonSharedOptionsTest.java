package pl.wsztajerowski.commands;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

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
    void fallsBackToUnknownRatherThanFailingAfterThePaidRun() {
        assertThat(parse("--results-table", "t").getProject()).isEqualTo("unknown");
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
