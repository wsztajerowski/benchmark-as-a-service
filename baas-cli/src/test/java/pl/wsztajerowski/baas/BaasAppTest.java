package pl.wsztajerowski.baas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BaasAppTest {

    @Test
    void adminSetupHelpResolvesThroughCommandTree() {
        CommandLine.ParseResult result = new CommandLine(new BaasApp())
            .parseArgs("admin", "setup", "--help");

        CommandLine.ParseResult adminResult = result.subcommand();
        assertThat(adminResult.commandSpec().name()).isEqualTo("admin");

        CommandLine.ParseResult setupResult = adminResult.subcommand();
        assertThat(setupResult.commandSpec().name()).isEqualTo("setup");
        assertThat(setupResult.isUsageHelpRequested()).isTrue();
    }

    @Test
    void adminTeardownHelpResolvesThroughCommandTree() {
        CommandLine.ParseResult result = new CommandLine(new BaasApp())
            .parseArgs("admin", "teardown", "--help");

        CommandLine.ParseResult adminResult = result.subcommand();
        assertThat(adminResult.commandSpec().name()).isEqualTo("admin");

        CommandLine.ParseResult teardownResult = adminResult.subcommand();
        assertThat(teardownResult.commandSpec().name()).isEqualTo("teardown");
        assertThat(teardownResult.isUsageHelpRequested()).isTrue();
    }

    @Test
    void resolvesConfigSyncSubcommand() {
        CommandLine cmd = new CommandLine(new BaasApp());

        assertThat(cmd.getSubcommands().get("config").getSubcommands())
            .containsKey("sync");
    }

    /**
     * Both commands wrote the same SSM SecureString, and nothing reads it since the cutover.
     * Asserted through the real command tree because that is how a user's script reaches them:
     * an option that parsed but did nothing would leave the operator believing they had
     * configured where measurements go.
     */
    @ParameterizedTest
    @ValueSource(strings = {"admin,setup", "config,set"})
    void mongoUriIsGoneFromEveryCommandThatOfferedIt(String command) {
        CommandLine root = new CommandLine(new BaasApp());
        String[] path = command.split(",");

        assertThatThrownBy(() -> root.parseArgs(
            path[0], path[1], "--mongo-uri", "mongodb+srv://user:pass@host/db"))
            .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
    }

    /**
     * Without the separator picocli reads JMH flags as baas options and fails with
     * "Unknown options: '-f', '-wi', '-i'", which does not hint at the cause. The help
     * text is the only place a user finds out, so it has to say so.
     */
    @Test
    void runHelpExplainsTheDoubleDashSeparator() {
        String usage = new CommandLine(new BaasApp())
            .getSubcommands().get("run")
            .getUsageMessage(CommandLine.Help.Ansi.OFF);

        assertThat(usage)
            .contains("Put -- before the benchmark parameters")
            .contains("baas run jmh -- MyBenchmark");
    }

    /** The -- separator has to survive into the forwarded parameter list, not be consumed. */
    @Test
    void parametersAfterTheSeparatorAreForwardedVerbatim() {
        CommandLine.ParseResult result = new CommandLine(new BaasApp())
            .parseArgs("run", "jmh", "--", "MyBenchmark", "-f", "1", "-wi", "1");

        assertThat(result.subcommand().matchedPositionalValue(1, java.util.List.<String>of()))
            .containsExactly("MyBenchmark", "-f", "1", "-wi", "1");
    }

    /**
     * SimpleLogger pins a logger's level when the logger is constructed, and every baas command
     * holds a {@code static final Logger} built while picocli instantiates the subcommand tree —
     * before {@code execute()}. Only this pre-scan runs early enough, so the image commands added
     * later inherit -v only as long as it keeps working.
     */
    @ParameterizedTest
    @ValueSource(strings = {"build-image", "image", "setup"})
    void argvPreScanRaisesVerbosityForAdminSubcommands(String subcommand) {
        System.clearProperty(LoggingMixin.LEVEL_PROPERTY);
        try {
            LoggingMixin.applyEarlyVerbosity(new String[]{"admin", subcommand, "-v"});

            assertThat(System.getProperty(LoggingMixin.LEVEL_PROPERTY)).isEqualTo("debug");
        } finally {
            System.clearProperty(LoggingMixin.LEVEL_PROPERTY);
        }
    }

    @Test
    void topLevelSetupIsNotResolvable() {
        CommandLine root = new CommandLine(new BaasApp());

        assertThatThrownBy(() -> root.parseArgs("setup"))
            .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
    }

    @Test
    void topLevelTeardownIsNotResolvable() {
        CommandLine root = new CommandLine(new BaasApp());

        assertThatThrownBy(() -> root.parseArgs("teardown"))
            .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
    }
}
