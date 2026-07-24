package pl.wsztajerowski.baas.commands.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SetupCommandTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "--github-org", "--github-repo", "--workflow-id", "--workflow-branch",
        "--oidc-provider-arn", "--prefix"
    })
    void rejectsRemovedGitHubOidcOptions(String removedOption) {
        CommandLine cmd = new CommandLine(new SetupCommand());

        assertThatThrownBy(() -> cmd.parseArgs(removedOption, "some-value"))
            .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
    }

    @Test
    void computePrefixIsDeterministicAndEightCharsLowerBase32() throws Exception {
        String arn = "arn:aws:iam::123456789012:user/dev-alice";

        String first = SetupCommand.computePrefix(arn);
        String second = SetupCommand.computePrefix(arn);

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(8);
        assertThat(first).isEqualTo(first.toLowerCase());
        assertThat(first).matches("[a-z2-7]{8}");
    }

    @Test
    void computePrefixDiffersForDifferentArns() throws Exception {
        String prefixA = SetupCommand.computePrefix("arn:aws:iam::123456789012:user/dev-alice");
        String prefixB = SetupCommand.computePrefix("arn:aws:iam::123456789012:user/dev-bob");

        assertThat(prefixA).isNotEqualTo(prefixB);
    }

    @Test
    void rejectsMongoUriWithoutDatabaseName() {
        assertThatThrownBy(() -> SetupCommand.validateMongoUri("mongodb+srv://user:pass@host"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("database name");
    }

    @Test
    void acceptsMongoUriWithDatabaseName() {
        SetupCommand.validateMongoUri("mongodb+srv://user:pass@host/benchmarks");
    }
}
