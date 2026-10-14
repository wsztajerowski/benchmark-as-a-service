package pl.wsztajerowski.baas.console;

import org.slf4j.Logger;

/**
 * Next-step hints: {@code → <purpose>: <exact command>}, at most two, written to standard error
 * through the logger and only when the console is interactive — by the same definition that gates
 * colour. A script or a CI log never sees one, so there is no option to turn them off.
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

    public static void show(Console console, Logger logger, String purpose, String command) {
        show(console, logger, purpose, command, null, null);
    }

    public static void show(Console console, Logger logger, String purpose, String command,
                            String secondPurpose, String secondCommand) {
        show(console, logger::info, purpose, command, secondPurpose, secondCommand);
    }

    /** The same, to any line sink — the logger in production, a list in tests. */
    public static void show(Console console, java.util.function.Consumer<String> sink, String purpose,
                            String command, String secondPurpose, String secondCommand) {
        if (!console.interactive()) {
            return;
        }
        sink.accept("→ " + purpose + ": " + command);
        if (secondPurpose != null) {
            sink.accept("→ " + secondPurpose + ": " + secondCommand);
        }
    }
}
