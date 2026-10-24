package pl.wsztajerowski.commands;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JCStressSubcommandTest {

    @Test
    void theModeIsLongFormOnly() {
        var result = new CommandLine(new JCStressSubcommand())
            .parseArgs("--mode", "sanity", "--project", "p", "--results-table", "t", "--s3-bucket", "b");

        assertThat(result.<String>matchedOptionValue("--mode", null)).isEqualTo("sanity");
    }

    /**
     * JCStress's own mode flag is -m, but on the runner -m means nothing: it was the MongoDB
     * connection string, and was left unassigned rather than made an alias, so an old -m URI is
     * refused instead of reaching JCStress as a mode.
     */
    @Test
    void theShortMIsNoRunnerOption() {
        assertThatThrownBy(() -> new CommandLine(new JCStressSubcommand())
            .parseArgs("-m", "sanity", "--project", "p", "--results-table", "t", "--s3-bucket", "b"))
            .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
    }
}
