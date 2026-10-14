package pl.wsztajerowski.baas.commands.admin.setup;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.console.Console;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PrompterTest {

    private final StringWriter out = new StringWriter();

    private Prompter answering(String... typed) {
        Deque<String> lines = new ArrayDeque<>(Arrays.asList(typed));
        return new Prompter(Console.withFlags(new PrintWriter(out, true), true, false),
            () -> lines.isEmpty() ? null : lines.pop());
    }

    @Test
    void aQuestionShowsItsDefaultAndAnEmptyLineTakesIt() {
        assertThat(answering("").ask("Deployment name", "baas-123456789012")).isEqualTo("baas-123456789012");
        assertThat(out.toString()).contains("Deployment name [baas-123456789012]: ");
    }

    @Test
    void anAnswerIsTrimmed() {
        assertThat(answering("  team-bench ").ask("Deployment name", "x")).isEqualTo("team-bench");
    }

    @Test
    void aClosedInputTakesTheDefaultForAQuestion() {
        assertThat(answering().ask("Region", "eu-central-1")).isEqualTo("eu-central-1");
    }

    /** A lost terminal must never approve a plan. */
    @Test
    void aClosedInputIsANoForAConfirmationEvenWhenYesIsTheDefault() {
        assertThat(answering().confirm("Go ahead?", true)).isFalse();
    }

    @Test
    void confirmationsTakeTheirDefaultOnAnEmptyLine() {
        assertThat(answering("").confirm("Go ahead?", true)).isTrue();
        assertThat(answering("").confirm("Change that?", false)).isFalse();
        assertThat(answering("Yes").confirm("Change that?", false)).isTrue();
        assertThat(answering("n").confirm("Go ahead?", true)).isFalse();
    }

    @Test
    void aChoiceIsNumberedAndAWrongAnswerIsAskedAgain() {
        assertThat(answering("7", "x", "2").choose("Which deployment?", List.of("a", "b", "new")))
            .contains(1);
        assertThat(out.toString()).contains("1) a", "2) b", "3) new", "Type a number from 1 to 3.");
    }

    @Test
    void aClosedInputChoosesNothing() {
        assertThat(answering().choose("Which deployment?", List.of("a", "b"))).isEmpty();
    }

    @Test
    void pauseReportsAClosedInput() {
        assertThat(answering("").pause("Press Enter")).isTrue();
        assertThat(answering().pause("Press Enter")).isFalse();
    }
}
