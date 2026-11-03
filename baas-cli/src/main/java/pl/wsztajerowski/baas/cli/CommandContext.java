package pl.wsztajerowski.baas.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.session.ConfigService;
import pl.wsztajerowski.baas.session.OperatorSession;

/**
 * What every command reaches its invocation through: the console its payload goes to, the
 * deployments, and the deployment's operator session — each built on first use, once (K9). A command
 * declares {@code @Mixin CommandContext context = new CommandContext()}; picocli keeps that instance
 * and injects the command's spec into it, and a test that constructs a command directly sets the
 * console or session through {@link #use(Console)} and {@link #use(OperatorSession)}.
 */
@Command // picocli accepts only an annotated class as a mixin; this one adds no option
public final class CommandContext {

    @Spec(Spec.Target.MIXEE)
    CommandSpec spec;

    private Console console;
    private OperatorSession operator;

    /** The command's payload console over picocli's {@code getOut()}; see {@link Console}. */
    public Console console() {
        if (console == null) {
            console = Console.of(spec.commandLine().getOut());
        }
        return console;
    }

    /** The deployments, bound to the {@code --deployment} the command line named. */
    public ConfigService configs() {
        return Invocation.configService(spec);
    }

    /** The selected deployment, as its operator role; opened on first use. */
    public OperatorSession operator() {
        if (operator == null) {
            operator = OperatorSession.of(configs());
        }
        return operator;
    }

    /** A test's console in place of picocli's output. */
    public CommandContext use(Console console) {
        this.console = console;
        return this;
    }

    /** A test's session in place of one opened from the deployment's files. */
    public CommandContext use(OperatorSession operator) {
        this.operator = operator;
        return this;
    }

    /** Whether a console was set or built: a command run outside picocli may have neither. */
    public boolean hasConsole() {
        return console != null || spec != null;
    }
}
