package pl.wsztajerowski.baas.commands;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import pl.wsztajerowski.baas.BaasApp;
import pl.wsztajerowski.baas.config.BaasConfig;

import static org.assertj.core.api.Assertions.assertThat;

class JobsDiffCommandTest {

    @Test
    void diffIsAVerbOfJobsAndEnvIsGone() {
        CommandLine root = new CommandLine(new BaasApp());

        assertThat(root.getSubcommands().get("jobs").getSubcommands())
            .as("comparing two jobs' environments is part of the execution domain")
            .containsKey("diff");
        assertThat(root.getSubcommands()).doesNotContainKey("env");
        assertThat(root.getSubcommands().get("admin").getSubcommands()).doesNotContainKey("diff");
    }

    @Test
    void diffTakesTwoResultPaths() {
        CommandLine.ParseResult result = new CommandLine(new BaasApp())
            .parseArgs("jobs", "diff", "main/jmh/20260724_120000", "main/jmh/20260811_093000");

        CommandLine.ParseResult diff = result.subcommand().subcommand();
        assertThat(diff.commandSpec().name()).isEqualTo("diff");
        assertThat(diff.matchedPositionalValue(0, "")).isEqualTo("main/jmh/20260724_120000");
        assertThat(diff.matchedPositionalValue(1, "")).isEqualTo("main/jmh/20260811_093000");
    }

    /**
     * `baas jobs diff` reads S3 and nothing else, so it belongs on operator credentials with the
     * other day-to-day commands — never on the deployer profile, which holds iam:CreateRole.
     */
    @Test
    void diffResolvesOperatorCredentials() {
        var config = new BaasConfig();
        config.getAws().setProfile("baas-deployer");
        config.getAws().setOperatorProfile("baas-operator");

        assertThat(config.getAws().resolveOperatorProfile())
            .as("JobsDiffSubcommand builds its S3 client from this accessor")
            .isEqualTo("baas-operator");

        assertThat(RunCommand.operatorCredentialsWarning(config))
            .as("and shares run/results' warning when no operator profile is set")
            .isEmpty();
    }

    @Test
    void helpNamesJobIdsAndTheGroupsCompared() {
        String usage = new CommandLine(new BaasApp())
            .getSubcommands().get("jobs").getSubcommands().get("diff")
            .getUsageMessage(CommandLine.Help.Ansi.OFF);

        assertThat(usage).contains("baas jobs list", "machine, cpu, memory, os, jvm, tools")
            .doesNotContain("result path");
    }

    /** U4: a job id is accepted, and an unknown one fails naming itself — before S3 is read. */
    @Test
    void anUnknownJobIdFailsWithoutTheMisleadingManifestMessage(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
        throws Exception {
        pl.wsztajerowski.baas.TestDeployments.write(dir, pl.wsztajerowski.baas.TestDeployments.DEFAULT);
        var lookedUp = new java.util.ArrayList<String>();
        var diff = new JobsDiffSubcommand() {
            @Override
            pl.wsztajerowski.baas.results.ResultsQueryService jobLookup(
                BaasConfig config, pl.wsztajerowski.baas.infra.AwsClientFactory factory) {
                return new pl.wsztajerowski.baas.results.ResultsQueryService(null, "t") {
                    @Override
                    public String resultPathForJob(String jobId) {
                        lookedUp.add(jobId);
                        return null;
                    }

                    @Override
                    public void close() {
                    }
                };
            }
        };
        CommandLine.IFactory defaults = CommandLine.defaultFactory();
        var cli = new CommandLine(new BaasApp(dir), new CommandLine.IFactory() {
            @Override
            @SuppressWarnings("unchecked")
            public <K> K create(Class<K> type) throws Exception {
                return type == JobsDiffSubcommand.class ? (K) diff : defaults.create(type);
            }
        });

        int exit = cli.execute("jobs", "diff",
            "20261002T000000000Z-00000000", "20261002T080250645Z-264f5dfb");

        assertThat(exit).isEqualTo(1);
        assertThat(lookedUp).containsExactly("20261002T000000000Z-00000000");
    }
}
