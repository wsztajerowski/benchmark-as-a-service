package pl.wsztajerowski.baas.commands;

import java.util.Map;
import java.nio.file.Path;
import java.nio.file.Files;
import pl.wsztajerowski.baas.config.ConfigService;
import pl.wsztajerowski.baas.BaasApp;
import org.junit.jupiter.api.io.TempDir;
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

    /**
     * A second installation's configuration is created by sync, in the named file only. The stack
     * lookup is stood in for: the test has no stack, and the file handling is what is under test.
     */
    @Test
    void syncWritesTheNamedFileAndLeavesTheDefaultAlone(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("dev.yaml");
        String defaultBefore = Files.exists(ConfigService.DEFAULT_PATH)
            ? Files.readString(ConfigService.DEFAULT_PATH) : null;

        int exit = new CommandLine(new BaasApp(), stackExists(Map.of("ResultsTableName", "t")))
            .execute("--config-path", file.toString(), "config", "sync", "--name", "baas-123456789012-dev");

        assertThat(exit).isZero();
        assertThat(ConfigService.at(file).load().getPrefix()).isEqualTo("baas-123456789012-dev");
        assertThat(Files.exists(ConfigService.DEFAULT_PATH)
            ? Files.readString(ConfigService.DEFAULT_PATH) : null).isEqualTo(defaultBefore);
    }

    @Test
    void aMissingStackWritesNothing(@TempDir Path dir) {
        Path file = dir.resolve("dev.yaml");

        int exit = new CommandLine(new BaasApp(), stackExists(Map.of()))
            .execute("--config-path", file.toString(), "config", "sync", "--name", "baas-nope");

        assertThat(exit).isNotZero();
        assertThat(file).doesNotExist();
    }

    private static CommandLine.IFactory stackExists(Map<String, String> outputs) {
        CommandLine.IFactory defaults = CommandLine.defaultFactory();
        return new CommandLine.IFactory() {
            @Override
            @SuppressWarnings("unchecked")
            public <K> K create(Class<K> cls) throws Exception {
                if (cls == ConfigSyncSubcommand.class) {
                    return (K) new ConfigSyncSubcommand() {
                        @Override
                        Map<String, String> stackOutputs(pl.wsztajerowski.baas.config.BaasConfig config) {
                            return outputs;
                        }
                    };
                }
                return defaults.create(cls);
            }
        };
    }
}
