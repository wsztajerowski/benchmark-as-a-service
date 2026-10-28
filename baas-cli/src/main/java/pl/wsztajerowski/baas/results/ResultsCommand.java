package pl.wsztajerowski.baas.results;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;
import pl.wsztajerowski.baas.cli.LoggingMixin;

/**
 * The measurements domain: {@code query} reads them, {@code diff} compares two jobs' worth. No second
 * verb over the same rows with overlapping filters — that would leave the user guessing where one
 * ends and the other begins — which is why {@code diff} takes two job ids and no filter at all.
 */
@Command(
    name = "results",
    mixinStandardHelpOptions = true,
    description = "The measurements: query benchmark results, or compare two jobs'.",
    subcommands = {ResultsQuerySubcommand.class, ResultsDiffSubcommand.class}
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
