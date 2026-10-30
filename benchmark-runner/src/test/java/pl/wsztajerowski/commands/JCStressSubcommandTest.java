package pl.wsztajerowski.commands;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JCStressSubcommandTest {

    /** JCStress's own -m reaches it after the separator; the runner has no mode option of its own. */
    @Test
    void theModeIsJCStressesOwnShortOption() {
        var subcommand = new JCStressSubcommand();
        new CommandLine(subcommand)
            .parseArgs("--project", "p", "--results-table", "t", "--s3-bucket", "b", "--", "-m", "sanity");

        assertThat(subcommand.benchmarkArgs).containsExactly("-m", "sanity");
    }

    /**
     * -m was the MongoDB connection string, then left unassigned; it is still no runner option, so
     * before the separator it is refused rather than read as anything.
     */
    @Test
    void theFormerModeOptionIsGone() {
        assertThatThrownBy(() -> new CommandLine(new JCStressSubcommand())
            .parseArgs("--mode", "sanity", "--project", "p", "--results-table", "t", "--s3-bucket", "b"))
            .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
    }
}
