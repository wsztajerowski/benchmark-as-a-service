package pl.wsztajerowski.baas.commands;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import pl.wsztajerowski.baas.BaasApp;
import pl.wsztajerowski.baas.config.BaasConfig;

import static org.assertj.core.api.Assertions.assertThat;

class EnvCommandTest {

    @Test
    void envIsTopLevelAndNotUnderAdmin() {
        CommandLine root = new CommandLine(new BaasApp());

        assertThat(root.getSubcommands())
            .as("it is a read-only day-to-day command, alongside run and results")
            .containsKey("env");
        assertThat(root.getSubcommands().get("admin").getSubcommands())
            .doesNotContainKey("env");
    }

    @Test
    void diffTakesTwoResultPaths() {
        CommandLine.ParseResult result = new CommandLine(new BaasApp())
            .parseArgs("env", "diff", "main/jmh/20260724_120000", "main/jmh/20260811_093000");

        CommandLine.ParseResult diff = result.subcommand().subcommand();
        assertThat(diff.commandSpec().name()).isEqualTo("diff");
        assertThat(diff.matchedPositionalValue(0, "")).isEqualTo("main/jmh/20260724_120000");
        assertThat(diff.matchedPositionalValue(1, "")).isEqualTo("main/jmh/20260811_093000");
    }

    /**
     * `baas env diff` reads S3 and nothing else, so it belongs on operator credentials with the
     * other day-to-day commands — never on the deployer profile, which holds iam:CreateRole.
     */
    @Test
    void diffResolvesOperatorCredentials() {
        var config = new BaasConfig();
        config.getAws().setProfile("baas-deployer");
        config.getAws().setOperatorProfile("baas-operator");

        assertThat(config.getAws().resolveOperatorProfile())
            .as("EnvDiffSubcommand builds its S3 client from this accessor")
            .isEqualTo("baas-operator");

        assertThat(RunCommand.operatorCredentialsWarning(config))
            .as("and shares run/results' warning when no operator profile is set")
            .isEmpty();
    }

    @Test
    void helpExplainsWhereResultPathsComeFrom() {
        String usage = new CommandLine(new BaasApp())
            .getSubcommands().get("env").getSubcommands().get("diff")
            .getUsageMessage(CommandLine.Help.Ansi.OFF);

        assertThat(usage).contains("runs/<project>/<runId>");
        assertThat(usage)
            .as("a run stored before the unified layout keeps its original path, and still resolves")
            .contains("<branch>/<type>/<timestamp>");
    }
}
