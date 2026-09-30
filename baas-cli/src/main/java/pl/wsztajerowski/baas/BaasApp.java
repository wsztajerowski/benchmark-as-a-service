package pl.wsztajerowski.baas;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;
import picocli.CommandLine.ParseResult;
import pl.wsztajerowski.baas.commands.ConfigCommand;
import pl.wsztajerowski.baas.commands.DownloadCommand;
import pl.wsztajerowski.baas.commands.EnvCommand;
import pl.wsztajerowski.baas.commands.ResultsCommand;
import pl.wsztajerowski.baas.commands.RunCommand;
import pl.wsztajerowski.baas.commands.admin.AdminCommand;

@Command(
    name = "baas",
    mixinStandardHelpOptions = true,
    versionProvider = BaasApp.VersionProvider.class,
    description = "Benchmark as a Service CLI — provision AWS infrastructure and run benchmarks.",
    subcommands = {
        AdminCommand.class,
        ConfigCommand.class,
        RunCommand.class,
        ResultsCommand.class,
        DownloadCommand.class,
        EnvCommand.class
    },
    // picocli lists direct children only, so `baas admin deployer-policy` is invisible here —
    // and it is the one command a new user needs *before* anything else works. Lines stay under
    // 80 columns: the footer wraps at the usage width and re-wrapping mid-command is unreadable.
    footer = {
        "",
        "First run, in order:",
        "  baas admin deployer-policy             # attach to your own identity",
        "  baas admin setup                       # deploy the stack",
        "  baas admin build-image                 # bake the runner AMI (~15 min)",
        "  baas config set --operator-profile <p> # day-to-day credentials",
        "  baas run jmh -- MyBenchmark -f 1       # note the -- separator",
        "",
        "See infra/README.md for the one-time IAM step."
    }
)
public class BaasApp implements Runnable {

    /**
     * The version cannot be a constant {@code version = ...} attribute: it is read from the JAR
     * manifest at run time, and a reactor build legitimately has none. Reporting the placeholder
     * unchanged is the point — {@code baas run} refuses to launch on it, so a user asking why
     * needs {@code -V} to say which build they are holding.
     */
    public static class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[]{"baas " + BaasVersion.current()};
        }
    }

    @Mixin LoggingMixin loggingMixin;

    @Spec CommandSpec spec;

    public static void main(String[] args) {
        // Must happen before the CommandLine is built — see LoggingMixin#applyEarlyVerbosity.
        LoggingMixin.applyEarlyVerbosity(args);
        BaasApp app = new BaasApp();
        System.exit(new CommandLine(app)
            .setExecutionStrategy(app::executionStrategy)
            .setExecutionExceptionHandler(BaasApp::reportFailure)
            .execute(args));
    }

    private int executionStrategy(ParseResult parseResult) {
        if (loggingMixin.verbose) {
            System.setProperty(LoggingMixin.LEVEL_PROPERTY, "debug");
        }
        return new CommandLine.RunLast().execute(parseResult); // default execution strategy
    }

    /**
     * A stack trace names SDK internals, not anything the user can act on. Log the message
     * — which for stack failures carries the CloudFormation reason — and keep the trace at debug,
     * behind {@code -v}, for when the message genuinely is not enough.
     *
     * <p>The logger is looked up here, not held in a {@code static final} field: {@code BaasApp}
     * is initialised before {@code main} runs {@link LoggingMixin#applyEarlyVerbosity}, and
     * SimpleLogger pins a logger's level at construction, so a static one would never see
     * {@code -v} and the trace would be unreachable.
     */
    private static int reportFailure(Exception ex, CommandLine commandLine,
                                     CommandLine.ParseResult parseResult) {
        Logger logger = LoggerFactory.getLogger(BaasApp.class);
        String message = ex.getMessage() != null ? ex.getMessage() : ex.toString();
        logger.error("{}", message);
        if (logger.isDebugEnabled()) {
            logger.debug("Stack trace:", ex);
        } else {
            logger.info("(run with -v for the full stack trace)");
        }
        return commandLine.getCommandSpec().exitCodeOnExecutionException();
    }

    @Override
    public void run() {
        // Help text is program output, not a log event — picocli renders and wraps it itself, onto
        // the same writer every other payload uses so the two cannot interleave out of order.
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
