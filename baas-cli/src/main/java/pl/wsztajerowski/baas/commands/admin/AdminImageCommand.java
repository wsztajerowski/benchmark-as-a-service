package pl.wsztajerowski.baas.commands.admin;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;
import pl.wsztajerowski.baas.LoggingMixin;

/** The runner AMI: build it, or show the one published. */
@Command(
    name = "image",
    mixinStandardHelpOptions = true,
    description = "The runner AMI: build, show.",
    subcommands = {ImageBuildSubcommand.class, ImageShowSubcommand.class}
)
public class AdminImageCommand implements Runnable {

    @Mixin LoggingMixin loggingMixin;

    @Spec CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
