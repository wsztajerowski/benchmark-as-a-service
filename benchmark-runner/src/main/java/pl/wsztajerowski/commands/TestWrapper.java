package pl.wsztajerowski.commands;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.ParseResult;

import static picocli.CommandLine.HelpCommand;

@Command(name = "test-runner",
    description = "Wrapper for benchmarks run and processing it's results.",
    subcommands = {HelpCommand.class, JmhSubcommand.class, JmhWithAsyncProfilerSubcommand.class, JCStressSubcommand.class})
public class TestWrapper {

    @Mixin LoggingMixin loggingMixin;

    public static void main(String... args) {
        TestWrapper app = new TestWrapper();
        int exitCode = new CommandLine(app)
            .setExecutionStrategy(app::executionStrategy)
            .execute(args);
        if (exitCode != CommandLine.ExitCode.OK ) {
            System.exit(exitCode);
        }
    }

    private int executionStrategy(ParseResult parseResult) {
        if (loggingMixin.verbose){
            System.setProperty("org.slf4j.simpleLogger.log.pl.wsztajerowski", "debug");
        }
        return new CommandLine.RunLast().execute(parseResult); // default execution strategy
    }
}
