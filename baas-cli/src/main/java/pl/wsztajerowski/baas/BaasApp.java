package pl.wsztajerowski.baas;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import pl.wsztajerowski.baas.commands.ConfigCommand;
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
        ResultsCommand.class
    }
)
public class BaasApp implements Runnable {

    public static void main(String[] args) {
        System.exit(new CommandLine(new BaasApp())
            .setExecutionExceptionHandler(BaasApp::reportFailure)
            .execute(args));
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
        CommandLine.usage(this, System.out);
    }
}
