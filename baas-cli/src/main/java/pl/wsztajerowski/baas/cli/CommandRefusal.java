package pl.wsztajerowski.baas.cli;

import java.util.List;
import java.util.Locale;

/**
 * A command declining what it was asked, with the exit code that says why: 2 for a request that
 * could never succeed as typed, 1 for one the deployment's state refused. Thrown, not returned, so
 * a validation is one line; {@code BaasApp.reportFailure} logs the message once and exits with the
 * code, without the {@code -v} hint a refusal's stack trace would not answer.
 */
public class CommandRefusal extends RuntimeException {

    private final int exitCode;

    public CommandRefusal(int exitCode, String message) {
        super(message, null, false, false);
        this.exitCode = exitCode;
    }

    /** Exit 2: the request is wrong as typed — an unknown value, a conflict between options. */
    public static CommandRefusal usage(String message) {
        return new CommandRefusal(2, message);
    }

    /** Exit 1: the request is well-formed, and what it names refused it. */
    public static CommandRefusal refused(String message) {
        return new CommandRefusal(1, message);
    }

    /**
     * Refuses a {@code --format} that is not one of {@code valid}, compared ignoring case, naming the
     * valid ones (K4). An unknown format used to fall through to the default, so {@code --format jsno}
     * silently printed a table to a pipeline expecting JSON.
     */
    public static void requireFormat(String format, List<String> valid) {
        if (format != null && !valid.contains(format.toLowerCase(Locale.ROOT))) {
            throw usage("Unknown --format '" + format + "'. Valid: " + String.join(", ", valid) + ".");
        }
    }

    public int exitCode() {
        return exitCode;
    }
}
