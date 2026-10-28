package pl.wsztajerowski.baas;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.console.Hints;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HelpAndHintsTest {

    @Test
    void theRootHelpIsGroupedByWorkload() {
        String help = BaasApp.commandLine(new BaasApp()).getUsageMessage(CommandLine.Help.Ansi.OFF);

        assertThat(help).contains("run = jobs run", "query = results query");
        int shortcuts = help.indexOf("Shortcuts");
        int operator = help.indexOf("Operator commands (operator AWS credentials)");
        int deployer = help.indexOf("Deployer commands (deployer AWS credentials)");
        assertThat(shortcuts).isNotNegative().isLessThan(operator);
        assertThat(operator).isLessThan(deployer);
        assertThat(help.substring(deployer)).contains("admin deployment", "setup, teardown, export, import", "admin image", "build, show");
        assertThat(help.substring(operator, deployer)).contains("run, list, show, diff, download, terminate, connect");
        assertThat(help.substring(operator, deployer)).containsPattern("results +query, diff");
    }

    @Test
    void theHelpNamesNoConfigurationKey() {
        String help = BaasApp.commandLine(new BaasApp()).getUsageMessage(CommandLine.Help.Ansi.OFF);

        assertThat(help).doesNotContain("aws.profile", "operatorProfile", "Commands:");
    }

    @Test
    void theFirstRunGuidanceStartsWithSetupThenTheImage() {
        String help = BaasApp.commandLine(new BaasApp()).getUsageMessage(CommandLine.Help.Ansi.OFF);

        assertThat(help.indexOf("baas admin deployment setup")).isLessThan(help.indexOf("baas admin image build"));
        assertThat(help).doesNotContain("deployer-policy");
    }

    @Test
    void anInteractiveConsoleGetsAtMostTwoHints() {
        List<String> lines = new ArrayList<>();
        Hints.show(Console.withFlags(new PrintWriter(new StringWriter()), true, false), lines::add,
            "measurements", "baas query --job-id x", "files", "baas jobs download x");

        assertThat(lines).containsExactly(
            "Next", "  measurements  baas query --job-id x", "  files         baas jobs download x");
    }

    @Test
    void oneHintIsABlockOfTwoLines() {
        List<String> lines = new ArrayList<>();
        Hints.show(Console.withFlags(new PrintWriter(new StringWriter()), true, false), lines::add,
            "stop one", "baas jobs terminate x", null, null);

        assertThat(lines).containsExactly("Next", "  stop one  baas jobs terminate x");
    }

    /** No timestamp, level or logger name: the block is written as is, and flushed. */
    @Test
    void theBlockGoesToTheErrorWriterAsIs() {
        var err = new StringWriter();
        Hints.show(Console.withFlags(new PrintWriter(new StringWriter()), true, false), new PrintWriter(err),
            "measurements", "baas query --job-id x", "files", "baas jobs download x");

        assertThat(err.toString().lines()).containsExactly(
            "Next", "  measurements  baas query --job-id x", "  files         baas jobs download x");
    }

    @Test
    void scriptsSeeNoHints() {
        List<String> lines = new ArrayList<>();
        var out = new StringWriter();
        Hints.show(Console.plain(new PrintWriter(out)), lines::add, "measurements", "baas query --job-id x", null, null);

        assertThat(lines).isEmpty();
        assertThat(out.toString()).isEmpty();
    }

    /** A hint printed with one deployment must still work once a second exists (E2). */
    @Test
    void aHintedCommandAlwaysNamesItsDeployment() {
        assertThat(Hints.command("baas-123456789012", "query --job-id x"))
            .isEqualTo("baas --deployment baas-123456789012 query --job-id x");
    }

    @Test
    void theDeploymentOptionStatesWhenItIsNeeded() {
        String help = BaasApp.commandLine(new BaasApp()).getSubcommands().get("results")
            .getSubcommands().get("query").getUsageMessage(CommandLine.Help.Ansi.OFF);

        assertThat(help.replaceAll("\\s+", " "))
            .contains("Optional when exactly one is configured on this machine")
            .contains("required with two or more")
            .contains("Printed hints always include it");
    }
}
