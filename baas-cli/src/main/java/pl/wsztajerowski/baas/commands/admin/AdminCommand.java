package pl.wsztajerowski.baas.commands.admin;

import picocli.CommandLine;
import picocli.CommandLine.Command;

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

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
