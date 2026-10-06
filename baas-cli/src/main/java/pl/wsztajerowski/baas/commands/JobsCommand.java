package pl.wsztajerowski.baas.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;
import pl.wsztajerowski.baas.LoggingMixin;

/**
 * Subcommands only, like every other group: a group with an action of its own would make a
 * mistyped subcommand parse as that action plus an unknown argument, and would put the destructive
 * {@code terminate} beside read-only listing options.
 */
@Command(
    name = "jobs",
    mixinStandardHelpOptions = true,
    description = "List benchmark jobs, and stop one that is still in flight.",
    subcommands = {JobsListSubcommand.class, JobsTerminateSubcommand.class}
)
public class JobsCommand implements Runnable {

    @Mixin LoggingMixin loggingMixin;

    @Spec CommandSpec spec;

    @Override
    public void run() {
        // Help text is program output, not a log event; see EnvCommand.
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
