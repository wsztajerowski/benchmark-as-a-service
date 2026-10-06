package pl.wsztajerowski.baas.commands.admin;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;
import pl.wsztajerowski.baas.LoggingMixin;

/** The deployment's AWS resources: set up (creating or updating) and tear down. */
@Command(
    name = "deployment",
    mixinStandardHelpOptions = true,
    description = "The deployment's AWS resources: setup, teardown.",
    subcommands = {SetupCommand.class, TeardownCommand.class}
)
public class DeploymentCommand implements Runnable {

    @Mixin LoggingMixin loggingMixin;

    @Spec CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
