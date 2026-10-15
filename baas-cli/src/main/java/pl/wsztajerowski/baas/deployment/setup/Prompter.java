package pl.wsztajerowski.baas.deployment.setup;

import pl.wsztajerowski.baas.console.Console;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Line-read prompts. Only an interactive run asks, so a prompt never reaches a redirect. A closed
 * input reads as {@code null}: a question then takes its default, and a confirmation is a no, so a
 * lost terminal can never approve a plan.
 */
final class Prompter {

    private final Console console;
    private final Supplier<String> reader;

    Prompter(Console console, Supplier<String> reader) {
        this.console = console;
        this.reader = reader;
    }

    /** The answer, or {@code defaultValue} for an empty line or a closed input. */
    String ask(String question, String defaultValue) {
        return answer(question, defaultValue).orElse(defaultValue);
    }

    /**
     * The answer, {@code defaultValue} for an empty line ({@code ""} when there is none), and empty
     * only on a closed input — for a question asked until it is answered, where taking the default
     * on a closed input would ask forever.
     */
    Optional<String> answer(String question, String defaultValue) {
        console.print(defaultValue == null || defaultValue.isEmpty()
            ? question + ": "
            : question + " [" + defaultValue + "]: ");
        String answer = reader.get();
        if (answer == null) {
            return Optional.empty();
        }
        return Optional.of(answer.isBlank() ? Objects.requireNonNullElse(defaultValue, "") : answer.strip());
    }

    boolean confirm(String question, boolean defaultYes) {
        console.print(question + (defaultYes ? " [Y/n]: " : " [y/N]: "));
        String answer = reader.get();
        if (answer == null) {
            return false;
        }
        String a = answer.strip().toLowerCase(java.util.Locale.ROOT);
        return a.isEmpty() ? defaultYes : a.startsWith("y");
    }

    /** The index of the chosen option, the first by default; empty on a closed input. */
    Optional<Integer> choose(String question, List<String> options) {
        console.println(question);
        for (int i = 0; i < options.size(); i++) {
            console.println("  " + (i + 1) + ") " + options.get(i));
        }
        while (true) {
            console.print("Choose [1]: ");
            String answer = reader.get();
            if (answer == null) {
                return Optional.empty();
            }
            if (answer.isBlank()) {
                return Optional.of(0);
            }
            try {
                int chosen = Integer.parseInt(answer.strip());
                if (chosen >= 1 && chosen <= options.size()) {
                    return Optional.of(chosen - 1);
                }
            } catch (NumberFormatException ignored) {
                // asked again below
            }
            console.println("Type a number from 1 to " + options.size() + ".");
        }
    }

    /** Waits for Enter; {@code false} on a closed input. */
    boolean pause(String message) {
        console.print(message + " ");
        return reader.get() != null;
    }

    Console console() {
        return console;
    }
}
