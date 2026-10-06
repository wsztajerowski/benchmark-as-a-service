package pl.wsztajerowski.baas.commands;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;
import pl.wsztajerowski.baas.BaasApp;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The construction: `baas [admin] <noun> <verb>`, with a top-level alias only for a verb that
 * belongs to exactly one noun. These pin the tree, not any command's behaviour.
 */
class CommandTreeTest {

    private static CommandLine root() {
        return new CommandLine(new BaasApp());
    }

    @Test
    void theRootHoldsNounsAndTheTwoAliasesOnly() {
        assertThat(root().getSubcommands().keySet())
            .containsExactlyInAnyOrder("admin", "config", "jobs", "results", "run", "query")
            .doesNotContain("download", "env");
    }

    @Test
    void eachNounOffersItsVerbs() {
        var root = root();
        assertThat(root.getSubcommands().get("jobs").getSubcommands().keySet())
            .contains("run", "list", "diff", "download", "terminate");
        assertThat(root.getSubcommands().get("results").getSubcommands().keySet())
            .containsExactlyInAnyOrder("query");
        var admin = root.getSubcommands().get("admin").getSubcommands();
        assertThat(admin.keySet()).containsExactlyInAnyOrder("deployment", "image");
        assertThat(admin.get("deployment").getSubcommands().keySet()).contains("setup", "teardown");
        assertThat(admin.get("image").getSubcommands().keySet()).contains("build", "show");
    }

    @Test
    void runIsTheSameCommandUnderBothSpellings() {
        String[] tail = {"--benchmark-jar", "b.jar", "--project", "p", "jmh", "--", "MyBenchmark", "-f", "1"};
        var aliased = root().parseArgs(concat(new String[]{"run"}, tail));
        var canonical = root().parseArgs(concat(new String[]{"jobs", "run"}, tail));

        RunCommand viaAlias = (RunCommand) aliased.subcommand().commandSpec().userObject();
        RunCommand viaNoun = (RunCommand) canonical.subcommand().subcommand().commandSpec().userObject();
        assertThat(viaAlias.benchmarkType).isEqualTo(viaNoun.benchmarkType).isEqualTo("jmh");
        assertThat(aliased.subcommand().matchedOptions()).hasSameSizeAs(canonical.subcommand().subcommand().matchedOptions());
    }

    @Test
    void queryIsTheSameCommandUnderBothSpellings() {
        var aliased = root().parseArgs("query", "--project", "p");
        var canonical = root().parseArgs("results", "query", "--project", "p");

        assertThat(aliased.subcommand().commandSpec().userObject()).isInstanceOf(ResultsQuerySubcommand.class);
        assertThat(canonical.subcommand().subcommand().commandSpec().userObject())
            .isInstanceOf(ResultsQuerySubcommand.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jobs", "results", "admin", "admin deployment", "admin image"})
    void aBareNounPrintsItsUsageAndExitsZero(String line) {
        var out = new StringWriter();
        var commandLine = root();
        commandLine.setOut(new PrintWriter(out, true));

        assertThat(commandLine.execute(line.split(" "))).isZero();
        assertThat(out.toString()).contains("Usage:");
    }

    @ParameterizedTest
    @ValueSource(strings = {"download x", "env diff a b", "list", "show x", "admin setup",
        "admin teardown", "admin build-image", "admin deployer-policy"})
    void removedAndNeverAliasedSpellingsAreUnknown(String line) {
        var err = new StringWriter();
        var commandLine = root();
        commandLine.setErr(new PrintWriter(err, true));

        assertThat(commandLine.execute(line.split(" "))).isNotZero();
    }

    private static String[] concat(String[] a, String[] b) {
        var all = new java.util.ArrayList<>(List.of(a));
        all.addAll(List.of(b));
        return all.toArray(String[]::new);
    }
}
