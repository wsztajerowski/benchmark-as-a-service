package pl.wsztajerowski.baas.commands.admin;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import pl.wsztajerowski.baas.LoggingMixin;

@Command(
    name = "admin",
    mixinStandardHelpOptions = true,
    description = "Deployer-privileged commands (requires BaasCliDeployerPolicy).",
    subcommands = {
        SetupCommand.class,
        TeardownCommand.class
    }
)
public class AdminCommand implements Runnable {

    @Mixin LoggingMixin loggingMixin;

    @Override
    public void run() {
        // Help text is program output, not a log event — picocli renders and wraps it itself.
        CommandLine.usage(this, System.out);
    }
}
