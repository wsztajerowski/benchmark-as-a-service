package pl.wsztajerowski.baas;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
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

    @Mixin LoggingMixin loggingMixin;

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
     * A stack trace names SDK internals, not anything the user can act on. Print the message
     * — which for stack failures carries the CloudFormation reason — and keep the trace behind
     * BAAS_DEBUG for when the message genuinely is not enough.
     */
    private static int reportFailure(Exception ex, CommandLine commandLine,
                                     CommandLine.ParseResult parseResult) {
        String message = ex.getMessage() != null ? ex.getMessage() : ex.toString();
        commandLine.getErr().println(commandLine.getColorScheme().errorText(message));
        if (System.getenv("BAAS_DEBUG") != null) {
            ex.printStackTrace(commandLine.getErr());
        } else {
            commandLine.getErr().println("(set BAAS_DEBUG=1 for the full stack trace)");
        }
        return commandLine.getCommandSpec().exitCodeOnExecutionException();
    }

    @Override
    public void run() {
        // Help text is program output, not a log event — picocli renders and wraps it itself.
        CommandLine.usage(this, System.out);
    }
}
