package pl.wsztajerowski.baas.cli;

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

    public int exitCode() {
        return exitCode;
    }
}
