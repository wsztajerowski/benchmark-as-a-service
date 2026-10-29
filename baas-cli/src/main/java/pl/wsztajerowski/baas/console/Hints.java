package pl.wsztajerowski.baas.console;

import java.io.PrintWriter;
import java.util.function.Consumer;

/**
 * Next-step hints: a {@code Next} line, then at most two {@code   <purpose>  <exact command>} lines
 * with the commands aligned, written to standard error and only when the console is interactive —
 * by the same definition that gates colour. A script or a CI log never sees one, so there is no
 * option to turn them off. Not through the logger: its timestamp, level and class name on every
 * line buried the command a person is meant to copy.
 */
public final class Hints {

    private Hints() {}

    /**
     * {@code baas --deployment <name> <rest>}. Every printed command that acts on a deployment
     * names it, however many are configured: a hint printed with one deployment then still works
     * once a second exists, instead of being refused as ambiguous.
     */
    public static String command(String deployment, String rest) {
        return "baas --deployment " + deployment + " " + rest;
    }

    /** To a command's standard-error writer, picocli's {@code getErr()}. */
    public static void show(Console console, PrintWriter err, String purpose, String command) {
        show(console, err, purpose, command, null, null);
    }

    public static void show(Console console, PrintWriter err, String purpose, String command,
                            String secondPurpose, String secondCommand) {
        show(console, (Consumer<String>) err::println, purpose, command, secondPurpose, secondCommand);
        err.flush();
    }

    /** The same, to any line sink — the error writer in production, a list in tests. */
    public static void show(Console console, Consumer<String> sink, String purpose,
                            String command, String secondPurpose, String secondCommand) {
        if (!console.interactive()) {
            return;
        }
        int width = Math.max(purpose.length(), secondPurpose == null ? 0 : secondPurpose.length());
        sink.accept("Next");
        sink.accept("  " + pad(purpose, width) + "  " + command);
        if (secondPurpose != null) {
            sink.accept("  " + pad(secondPurpose, width) + "  " + secondCommand);
        }
    }

    private static String pad(String text, int width) {
        return text + " ".repeat(width - text.length());
    }
}
