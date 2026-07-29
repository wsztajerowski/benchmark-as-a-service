package pl.wsztajerowski.baas.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import pl.wsztajerowski.baas.LoggingMixin;

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

    private static final Logger logger = LoggerFactory.getLogger(ConfigCommand.class);

    @Mixin LoggingMixin loggingMixin;

    @Override
    public void run() {
        logger.info("Use 'baas config set', 'baas config show', or 'baas config sync'.");
    }
}
