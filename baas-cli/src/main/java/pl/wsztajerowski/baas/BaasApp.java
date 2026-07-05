package pl.wsztajerowski.baas;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import pl.wsztajerowski.baas.commands.ConfigCommand;
import pl.wsztajerowski.baas.commands.ResultsCommand;
import pl.wsztajerowski.baas.commands.RunCommand;
import pl.wsztajerowski.baas.commands.SetupCommand;
import pl.wsztajerowski.baas.commands.TeardownCommand;

@Command(
    name = "baas",
    mixinStandardHelpOptions = true,
    description = "Benchmark as a Service CLI — provision AWS infrastructure and run benchmarks.",
    subcommands = {
        SetupCommand.class,
        TeardownCommand.class,
        ConfigCommand.class,
        RunCommand.class,
        ResultsCommand.class
    }
)
public class BaasApp implements Runnable {

    public static void main(String[] args) {
        System.exit(new CommandLine(new BaasApp()).execute(args));
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
