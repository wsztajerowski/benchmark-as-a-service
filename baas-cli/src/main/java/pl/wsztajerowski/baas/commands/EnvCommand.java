package pl.wsztajerowski.baas.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;
import pl.wsztajerowski.baas.LoggingMixin;

@Command(
    name = "env",
    mixinStandardHelpOptions = true,
    description = "Compare the environments two jobs measured on.",
    subcommands = EnvDiffSubcommand.class
)
public class EnvCommand implements Runnable {

    @Mixin LoggingMixin loggingMixin;

    @Spec CommandSpec spec;

    @Override
    public void run() {
        // Help text is program output, not a log event — picocli renders and wraps it itself, onto
        // the same writer every other payload uses so the two cannot interleave out of order.
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
