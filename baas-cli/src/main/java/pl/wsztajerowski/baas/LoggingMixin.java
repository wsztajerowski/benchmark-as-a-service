package pl.wsztajerowski.baas;

import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import static picocli.CommandLine.Spec.Target.MIXEE;

/* For details see: https://github.com/remkop/picocli/blob/main/picocli-examples/src/main/java/picocli/examples/logging_mixin_simple/ */
public class LoggingMixin {

    static final String LEVEL_PROPERTY = "org.slf4j.simpleLogger.log.pl.wsztajerowski";

    private @Spec(MIXEE) CommandSpec mixee; // spec of the command where the @Mixin is used

    boolean verbose;

    /**
     * Sets the specified verbosity on the LoggingMixin of the top-level command.
     * @param verbose the new verbosity value
     */
    @Option(names = {"-v", "--verbose"}, description = {
        "Print processing details (debug-level logging)."})
    public void setVerbose(boolean verbose) {
        // Each subcommand that mixes in the LoggingMixin has its own instance
        // of this class, so there may be many LoggingMixin instances.
        // We want to store the verbosity value in a single, central place,
        // so we find the top-level command,
        // and store the verbosity level on our top-level command's LoggingMixin.
        ((BaasApp) mixee.root().userObject()).loggingMixin.verbose = verbose;
    }

    /**
     * Raises the {@code pl.wsztajerowski} level to debug from a raw argv scan, before any
     * {@code CommandLine} exists.
     *
     * <p>Unlike benchmark-runner, {@code baas} cannot rely on the execution strategy alone.
     * SimpleLogger pins a logger's level when that logger is constructed, and every {@code baas}
     * command holds a {@code static final Logger} — all initialised while picocli instantiates the
     * subcommand tree, before {@code execute()} runs. Setting the property only in the execution
     * strategy leaves those loggers stuck at info (verified: {@code run -v} logged nothing at
     * debug until this scan was added).
     *
     * <p>This scan is early but approximate; picocli's own parse, applied in
     * {@code BaasApp.executionStrategy}, remains authoritative and still catches anything missed
     * here for the loggers built later inside a command body.
     */
    static void applyEarlyVerbosity(String[] args) {
        for (String arg : args) {
            // Stop at the JMH/JCStress separator: everything after it belongs to the runner, so a
            // `-v` there is the benchmark's own flag, not ours.
            if ("--".equals(arg)) {
                return;
            }
            // Second test covers clustered short options (-hv): of baas's short options only
            // verbose is a lowercase v, -V being --version.
            if ("--verbose".equals(arg)
                || (arg.startsWith("-") && !arg.startsWith("--") && arg.indexOf('v') > 0)) {
                System.setProperty(LEVEL_PROPERTY, "debug");
                return;
            }
        }
    }
}
