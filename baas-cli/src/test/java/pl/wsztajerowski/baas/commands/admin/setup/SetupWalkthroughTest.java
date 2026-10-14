package pl.wsztajerowski.baas.commands.admin.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.wsztajerowski.baas.config.ConfigService;
import pl.wsztajerowski.baas.console.Console;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** The engine alone, against stub steps: order, waiting, stopping and going back. No AWS at all. */
class SetupWalkthroughTest {

    @TempDir
    Path root;

    private final List<String> ran = new ArrayList<>();

    private SetupStep step(String name, Function<Integer, SetupStep.Outcome> outcomeByAttempt) {
        return new SetupStep() {
            int attempts;

            @Override
            public String name() {
                return name;
            }

            @Override
            public Outcome run(SetupContext context) {
                ran.add(name);
                return outcomeByAttempt.apply(++attempts);
            }
        };
    }

    private SetupStep passing(String name) {
        return step(name, attempt -> SetupStep.next());
    }

    private int run(boolean interactive, Deque<String> typed, SetupStep... steps) throws Exception {
        var console = Console.withFlags(new PrintWriter(new StringWriter()), interactive, false);
        var context = new SetupContext(
            new SetupOptions(Optional.empty(), null, null, null, null, List.of(), false, false),
            new ConfigService(root, Optional.empty()), interactive,
            new Prompter(console, () -> typed.isEmpty() ? null : typed.pop()), console,
            (region, profile) -> { throw new AssertionError("the engine never touches AWS"); },
            duration -> {});
        return new SetupWalkthrough(List.of(steps)).run(context);
    }

    @Test
    void stepsRunInOrderAndAFinishedRunExitsZero() throws Exception {
        int exit = run(false, new ArrayDeque<>(), passing("a"), passing("b"), passing("c"));

        assertThat(exit).isZero();
        assertThat(ran).containsExactly("a", "b", "c");
    }

    @Test
    void aWaitingStepRunsAgainOnEachEnter() throws Exception {
        var waiting = step("b", attempt -> attempt < 3 ? new SetupStep.Outcome.Wait("attach it") : SetupStep.next());

        int exit = run(true, new ArrayDeque<>(List.of("", "")), passing("a"), waiting, passing("c"));

        assertThat(exit).isZero();
        assertThat(ran).containsExactly("a", "b", "b", "b", "c");
    }

    @Test
    void unattendedAWaitingStepStopsWithExitOne() throws Exception {
        var waiting = step("b", attempt -> new SetupStep.Outcome.Wait("attach it"));

        int exit = run(false, new ArrayDeque<>(), passing("a"), waiting, passing("c"));

        assertThat(exit).isEqualTo(1);
        assertThat(ran).containsExactly("a", "b");
    }

    /** No timer: a step that keeps waiting is checked again only when a line is read. */
    @Test
    void aClosedInputStopsTheWaitInsteadOfCheckingAgainOnItsOwn() throws Exception {
        var waiting = step("b", attempt -> new SetupStep.Outcome.Wait("attach it"));

        int exit = run(true, new ArrayDeque<>(List.of("")), passing("a"), waiting, passing("c"));

        assertThat(exit).isEqualTo(1);
        assertThat(ran).containsExactly("a", "b", "b");
    }

    @Test
    void aWaitCanResumeAtAnEarlierStep() throws Exception {
        var failing = step("c", attempt -> attempt == 1 ? new SetupStep.Outcome.Wait("stale", "b") : SetupStep.next());

        int exit = run(true, new ArrayDeque<>(List.of("")), passing("a"), passing("b"), failing, passing("d"));

        assertThat(exit).isZero();
        assertThat(ran).containsExactly("a", "b", "c", "b", "c", "d");
    }

    @Test
    void goToContinuesFromTheNamedStep() throws Exception {
        var unusable = step("c", attempt -> attempt == 1 ? new SetupStep.Outcome.GoTo("a") : SetupStep.next());

        int exit = run(false, new ArrayDeque<>(), passing("a"), passing("b"), unusable);

        assertThat(exit).isZero();
        assertThat(ran).containsExactly("a", "b", "c", "a", "b", "c");
    }

    @Test
    void aStopEndsTheRunWithItsExitCode() throws Exception {
        var stopping = step("b", attempt -> new SetupStep.Outcome.Stop(2, "no"));

        assertThat(run(true, new ArrayDeque<>(), passing("a"), stopping, passing("c"))).isEqualTo(2);
        assertThat(ran).containsExactly("a", "b");
    }
}
