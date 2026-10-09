package pl.wsztajerowski.commands;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

class JCStressSubcommandTest {

    /**
     * JCStress's own mode flag is -m, but on the runner -m is the MongoDB connection string, so the
     * mode is long-form only until that option is retired.
     */
    @Test
    void theShortMStaysTheMongoConnectionStringBesideMode() {
        var result = new CommandLine(new JCStressSubcommand())
            .parseArgs("-m", "mongodb://h:27017/db", "--mode", "sanity", "--project", "p");

        assertThat(result.matchedOption("-m").longestName()).isEqualTo("--mongo-connection-string");
        assertThat(result.<String>matchedOptionValue("--mode", null)).isEqualTo("sanity");
    }
}
