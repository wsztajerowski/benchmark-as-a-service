package pl.wsztajerowski.baas;

import org.junit.jupiter.api.Test;
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
