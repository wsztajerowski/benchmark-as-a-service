package pl.wsztajerowski.baas.commands;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import pl.wsztajerowski.baas.BaasApp;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigSetOptionsTest {

    /**
     * Adopting an installation goes through `config sync --name`, which checks the stack exists.
     * `config set --prefix` wrote the same field unchecked.
     */
    @Test
    void thePrefixCannotBeSetUnchecked() {
        assertThatThrownBy(() -> new CommandLine(new BaasApp())
            .parseArgs("config", "set", "--prefix", "baas-123456789012"))
            .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
    }
}
