package pl.wsztajerowski.baas.commands;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigSyncSubcommandTest {

    /**
     * Required although the prefix is derivable. A bare sync on a machine with no local state
     * would adopt whatever installation the active credentials imply — in CI, that is a wrong role
     * or a leftover AWS_PROFILE binding the machine to another account's installation, discovered
     * only after something has been provisioned.
     */
    @Test
    void theInstallationNameIsRequired() {
        assertThatThrownBy(() -> new CommandLine(new ConfigSyncSubcommand()).parseArgs())
            .isInstanceOf(CommandLine.MissingParameterException.class)
            .hasMessageContaining("--name");
    }

    @Test
    void theRemovedCoreStackNameOptionIsGone() {
        // On the spec, not by parsing: --name is required, so picocli reports that first and a
        // parse-based assertion would pass whether or not --core-stack-name still existed.
        var names = new CommandLine(new ConfigSyncSubcommand()).getCommandSpec().options().stream()
            .flatMap(option -> java.util.Arrays.stream(option.names()))
            .toList();

        assertThat(names).contains("--name").doesNotContain("--core-stack-name");
    }

    @Test
    void theNameIsTheInstallationPrefix() {
        var command = new ConfigSyncSubcommand();
        new CommandLine(command).parseArgs("--name", "baas-123456789012-dev");

        assertThat(command.name).isEqualTo("baas-123456789012-dev");
    }
}
