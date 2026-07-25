package pl.wsztajerowski.baas.commands;

import picocli.CommandLine.Command;

@Command(
    name = "config",
    mixinStandardHelpOptions = true,
    description = "Manage BaaS CLI configuration.",
    subcommands = {
        ConfigSetSubcommand.class,
        ConfigShowSubcommand.class,
        ConfigSyncSubcommand.class
    }
)
public class ConfigCommand implements Runnable {

    @Override
    public void run() {
        System.out.println("Use 'baas config set', 'baas config show', or 'baas config sync'.");
    }
}
