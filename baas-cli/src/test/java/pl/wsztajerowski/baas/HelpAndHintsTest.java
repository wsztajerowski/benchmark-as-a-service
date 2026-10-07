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
        assertThat(help.substring(deployer)).contains("admin deployment", "setup, teardown", "admin image", "build, show");
        assertThat(help.substring(operator, deployer)).contains("run, list, show, diff, download, terminate");
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
            "→ measurements: baas query --job-id x", "→ files: baas jobs download x");
    }

    @Test
    void scriptsSeeNoHints() {
        List<String> lines = new ArrayList<>();
        var out = new StringWriter();
        Hints.show(Console.plain(new PrintWriter(out)), lines::add, "measurements", "baas query --job-id x", null, null);

        assertThat(lines).isEmpty();
        assertThat(out.toString()).isEmpty();
    }
}
