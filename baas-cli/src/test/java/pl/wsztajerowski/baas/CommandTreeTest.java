package pl.wsztajerowski.baas;

import pl.wsztajerowski.baas.jobs.RunCommand;
import pl.wsztajerowski.baas.results.ResultsQuerySubcommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The construction: `baas [admin] <noun> <verb>`, with a top-level alias only for a verb that
 * belongs to exactly one noun. These pin the tree, not any command's behaviour.
 */
class CommandTreeTest {

    private static CommandLine root() {
        return BaasApp.commandLine(new BaasApp());
    }

    @Test
    void theRootHoldsNounsAndTheTwoAliasesOnly() {
        assertThat(root().getSubcommands().keySet())
            .containsExactlyInAnyOrder("admin", "config", "jobs", "results", "run", "query")
            .doesNotContain("download", "env", "connect");
    }

    @Test
    void eachNounOffersItsVerbs() {
        var root = root();
        assertThat(root.getSubcommands().get("jobs").getSubcommands().keySet())
            .contains("run", "list", "show", "diff", "download", "terminate", "connect");
        assertThat(root.getSubcommands().get("results").getSubcommands().keySet())
            .containsExactlyInAnyOrder("query");
        var admin = root.getSubcommands().get("admin").getSubcommands();
        assertThat(admin.keySet()).containsExactlyInAnyOrder("deployment", "image");
        assertThat(admin.get("deployment").getSubcommands().keySet())
            .containsExactlyInAnyOrder("setup", "teardown", "export", "import");
        assertThat(admin.get("image").getSubcommands().keySet()).contains("build", "show");
    }

    /** A deployment's data moves only under `admin deployment`, with the deployer's credentials. */
    @Test
    void exportAndImportHaveNoOtherForm() {
        for (String[] args : new String[][]{{"export"}, {"import", "b"}, {"jobs", "export"}, {"admin", "export"}}) {
            assertThatThrownBy(() -> root().parseArgs(args))
                .as(String.join(" ", args))
                .isInstanceOf(picocli.CommandLine.UnmatchedArgumentException.class);
        }
    }

    @Test
    void runIsTheSameCommandUnderBothSpellings() {
        String[] tail = {"--benchmark-jar", "b.jar", "--project", "p", "jmh", "--", "MyBenchmark", "-f", "1"};
        var aliased = root().parseArgs(concat(new String[]{"run"}, tail));
        var canonical = root().parseArgs(concat(new String[]{"jobs", "run"}, tail));

        // Read through picocli, not the command's field: the command lives in its slice's package.
        var viaAlias = aliased.subcommand();
        var viaNoun = canonical.subcommand().subcommand();
        assertThat(viaAlias.commandSpec().userObject()).isInstanceOf(RunCommand.class);
        assertThat(viaNoun.commandSpec().userObject()).isInstanceOf(RunCommand.class);
        assertThat((String) viaAlias.matchedPositional(0).getValue())
            .isEqualTo(viaNoun.matchedPositional(0).getValue()).isEqualTo("jmh");
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
