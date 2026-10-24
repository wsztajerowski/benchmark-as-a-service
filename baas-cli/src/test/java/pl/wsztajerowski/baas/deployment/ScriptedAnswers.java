package pl.wsztajerowski.baas.deployment;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

/** Lines typed at a prompt, in order; a closed input ({@code null}) once they run out. */
final class ScriptedAnswers implements Supplier<String> {

    private final Deque<String> answers;
    int asked;

    ScriptedAnswers(String... answers) {
        this.answers = new ArrayDeque<>(List.of(answers));
    }

    @Override
    public String get() {
        asked++;
        return answers.poll();
    }
}
