package pl.symentis.commands;

import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import static picocli.CommandLine.Spec.Target.MIXEE;

/* For details see: https://github.com/remkop/picocli/blob/main/picocli-examples/src/main/java/picocli/examples/logging_mixin_simple/ */
public class LoggingMixin {
    private @Spec(MIXEE) CommandSpec mixee; // spec of the command where the @Mixin is used

    boolean verbose;

    /**
     * Sets the specified verbosity on the LoggingMixin of the top-level command.
     * @param verbose the new verbosity value
     */
    @Option(names = {"-v", "--verbose"}, description = {
        "Print processing details."})
    public void setVerbose(boolean verbose) {
        // Each subcommand that mixes in the LoggingMixin has its own instance
        // of this class, so there may be many LoggingMixin instances.
        // We want to store the verbosity value in a single, central place,
        // so we find the top-level command,
        // and store the verbosity level on our top-level command's LoggingMixin.
        ((TestWrapper) mixee.root().userObject()).loggingMixin.verbose = verbose;
    }
}