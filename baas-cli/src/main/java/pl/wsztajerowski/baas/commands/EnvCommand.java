package pl.wsztajerowski.baas.commands;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import pl.wsztajerowski.baas.LoggingMixin;

@Command(
    name = "env",
    mixinStandardHelpOptions = true,
    description = "Compare the environments two runs measured on.",
    subcommands = EnvDiffSubcommand.class
)
public class EnvCommand implements Runnable {

    @Mixin LoggingMixin loggingMixin;

    @Override
    public void run() {
        // Help text is program output, not a log event — picocli renders and wraps it itself.
        CommandLine.usage(this, System.out);
    }
}
