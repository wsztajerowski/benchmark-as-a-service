package pl.wsztajerowski.baas.commands.admin;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;
import pl.wsztajerowski.baas.LoggingMixin;

@Command(
    name = "admin",
    mixinStandardHelpOptions = true,
    description = {
        "Deployer commands (deployer AWS credentials).",
        "Start with `baas admin deployment setup`, which prints the policy your identity lacks."
    },
    subcommands = {
        DeploymentCommand.class,
        AdminImageCommand.class
    }
)
public class AdminCommand implements Runnable {

    @Mixin LoggingMixin loggingMixin;

    @Spec CommandSpec spec;

    @Override
    public void run() {
        // Help text is program output, not a log event — picocli renders and wraps it itself, onto
        // the same writer every other payload uses so the two cannot interleave out of order.
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
