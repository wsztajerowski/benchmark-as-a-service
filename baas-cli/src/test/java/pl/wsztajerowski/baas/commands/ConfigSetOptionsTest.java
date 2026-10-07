package pl.wsztajerowski.baas.commands;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import pl.wsztajerowski.baas.BaasApp;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigSetOptionsTest {

    /**
     * Adopting a deployment goes through `config sync --deployment`, which checks the stack exists.
     * `config set --prefix` wrote the same field unchecked.
     */
    @Test
    void thePrefixCannotBeSetUnchecked() {
        assertThatThrownBy(() -> new CommandLine(new BaasApp())
            .parseArgs("config", "set", "--prefix", "baas-123456789012"))
            .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
    }

    /**
     * The region is the deployment's: setup chooses it and sync finds it from the bucket. Set by
     * hand it aimed a machine at a region with no deployment.
     */
    @Test
    void theRegionCannotBeSetByHand() {
        assertThatThrownBy(() -> new CommandLine(new BaasApp())
            .parseArgs("config", "set", "--region", "us-east-1"))
            .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
    }
}
