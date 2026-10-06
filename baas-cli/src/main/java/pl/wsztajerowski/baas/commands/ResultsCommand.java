package pl.wsztajerowski.baas.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;
import pl.wsztajerowski.baas.LoggingMixin;

/**
 * The measurements domain. One verb, {@code query}: a second verb over the same rows with
 * overlapping filters would leave the user guessing where one ends and the other begins.
 */
@Command(
    name = "results",
    mixinStandardHelpOptions = true,
    description = "The measurements: query benchmark results.",
    subcommands = ResultsQuerySubcommand.class
)
public class ResultsCommand implements Runnable {

    @Mixin LoggingMixin loggingMixin;

    @Spec CommandSpec spec;

    @Override
    public void run() {
        // A noun alone prints its usage; help text is program output, not a log event.
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
