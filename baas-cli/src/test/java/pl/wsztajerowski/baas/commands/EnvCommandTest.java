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

        assertThat(usage).contains("runs/<project>/<runId>", "run id");
        assertThat(usage)
            .as("a run stored before the unified layout keeps its original path, and still resolves")
            .contains("<branch>/<type>/<timestamp>");
    }

    /** U4: a run id is accepted, and an unknown one fails naming itself — before S3 is read. */
    @Test
    void anUnknownRunIdFailsWithoutTheMisleadingManifestMessage(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
        throws Exception {
        var file = dir.resolve("config.yaml");
        java.nio.file.Files.writeString(file, "prefix: baas-123456789012\naws:\n  region: eu-central-1\n");
        var lookedUp = new java.util.ArrayList<String>();
        var diff = new EnvDiffSubcommand() {
            @Override
            pl.wsztajerowski.baas.results.ResultsQueryService runLookup(
                BaasConfig config, pl.wsztajerowski.baas.infra.AwsClientFactory factory) {
                return new pl.wsztajerowski.baas.results.ResultsQueryService(null, "t") {
                    @Override
                    public String resultPathForRun(String runId) {
                        lookedUp.add(runId);
                        return null;
                    }

                    @Override
                    public void close() {
                    }
                };
            }
        };
        CommandLine.IFactory defaults = CommandLine.defaultFactory();
        var cli = new CommandLine(new BaasApp(), new CommandLine.IFactory() {
            @Override
            @SuppressWarnings("unchecked")
            public <K> K create(Class<K> type) throws Exception {
                return type == EnvDiffSubcommand.class ? (K) diff : defaults.create(type);
            }
        });

        int exit = cli.execute("--config-path", file.toString(), "env", "diff",
            "20261002T000000000Z-00000000", "runs/p/20261002T080250645Z-264f5dfb");

        assertThat(exit).isEqualTo(1);
        assertThat(lookedUp).containsExactly("20261002T000000000Z-00000000");
    }
}
