package pl.wsztajerowski.baas.commands;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.results.ResultsQueryService;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which project {@code baas results} reads when none is named. Every case stops before a partition
 * is queried, so a stand-in listing is the only AWS-shaped thing involved.
 */
class ResultsProjectSelectionTest {

    private final StringWriter screen = new StringWriter();
    private final List<String> listings = new ArrayList<>();

    /** Answers the picker's listing and records that it was asked; anything else would be a bug. */
    private ResultsQueryService projects(String... names) {
        return new ResultsQueryService(null, "t") {
            @Override
            public List<String> listVisibleProjects() {
                listings.add("listed");
                return List.of(names);
            }
        };
    }

    private ResultsCommand command(boolean interactive, String answer, String... args) {
        var command = new ResultsCommand();
        new CommandLine(command).parseArgs(args);
        command.console = Console.withFlags(new PrintWriter(screen), interactive, false);
        command.answerReader = () -> answer;
        return command;
    }

    @Test
    void aNamedProjectIsUsedWithoutListingAnything() {
        var command = command(true, null, "--project", "lynx-journal");

        assertThat(command.resolveProject(new BaasConfig(), projects("a"))).contains("lynx-journal");
        assertThat(listings).isEmpty();
    }

    @Test
    void thePickerOffersTheListedProjectsWithTheGitTip() {
        var command = command(true, "2");

        Optional<String> chosen = command.resolveProject(new BaasConfig(), projects("a-real", "c-real"));

        assertThat(chosen).contains("c-real");
        assertThat(screen.toString())
            .contains(" 1) a-real", " 2) c-real")
            .contains("baas config set --git-resolve-project true");
    }

    @Test
    void anAnswerThatIsNotAListedNumberIsRefused() {
        assertThat(command(true, "3").resolveProject(new BaasConfig(), projects("a", "b"))).isEmpty();
        assertThat(command(true, "b").resolveProject(new BaasConfig(), projects("a", "b"))).isEmpty();
        assertThat(command(true, null).resolveProject(new BaasConfig(), projects("a", "b"))).isEmpty();
    }

    /** A redirect cannot answer a prompt; it gets a refusal listing the projects, not a hang. */
    @Test
    void withoutATerminalNoPromptIsShown() {
        var command = command(false, "1");

        assertThat(command.resolveProject(new BaasConfig(), projects("a"))).isEmpty();
        assertThat(screen.toString()).doesNotContain("Choose a project");
    }

    @Test
    void aMachineFormatIsNeverPrompted() {
        var command = command(true, "1", "--format", "json");

        assertThat(command.resolveProject(new BaasConfig(), projects("a"))).isEmpty();
        assertThat(screen.toString()).doesNotContain("Choose a project");
    }

    /**
     * The test runs inside this repository, so with derivation on the working directory names a
     * project and the picker never appears.
     */
    @Test
    void gitDerivationSkipsThePickerWhenEnabled() {
        var config = new BaasConfig();
        config.getGit().setResolveProject(true);

        Optional<String> chosen = command(true, "1").resolveProject(config, projects("a"));

        assertThat(chosen).isPresent().get().asString().isNotBlank().isNotEqualTo("a");
        assertThat(listings).isEmpty();
    }

    @Test
    void gitDerivationIsNotConsultedWhenOff() {
        Optional<String> chosen = command(true, "1").resolveProject(new BaasConfig(), projects("a"));

        assertThat(chosen).contains("a");
        assertThat(listings).hasSize(1);
    }

    // ─── option combinations, refused before any configuration is read ────────────

    @Test
    void oneProjectAndEveryProjectAreExclusive() throws Exception {
        assertThat(command(true, null, "--project", "p", "--all-projects").call()).isEqualTo(2);
    }

    @Test
    void aRunLookupCannotBeCombinedWithEveryProject() throws Exception {
        assertThat(command(true, null, "--request-id", "r", "--all-projects").call()).isEqualTo(2);
    }

    @Test
    void theRemovedOptionsAreUnknown() {
        for (String option : new String[]{"--living-branches", "--all", "--results-table"}) {
            var parser = new CommandLine(new ResultsCommand());

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> parser.parseArgs(option, "x"))
                .as(option)
                .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
        }
    }
}
