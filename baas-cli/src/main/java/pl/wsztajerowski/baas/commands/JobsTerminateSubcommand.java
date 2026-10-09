package pl.wsztajerowski.baas.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import pl.wsztajerowski.baas.BaasApp;
import pl.wsztajerowski.baas.LoggingMixin;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.infra.AwsClientFactory;
import pl.wsztajerowski.baas.infra.Ec2ProvisioningService;
import pl.wsztajerowski.baas.jobs.DynamoDbJobRecorder;
import pl.wsztajerowski.baas.jobs.JobTermination;

import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

@Command(
    name = "terminate",
    mixinStandardHelpOptions = true,
    description = "Stop a job: record it as cancelled and terminate its instance. Also stops a live "
        + "instance whose job already shows an outcome.",
    separator = " "
)
public class JobsTerminateSubcommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(JobsTerminateSubcommand.class);

    @Mixin LoggingMixin loggingMixin;

    @Spec CommandSpec spec;

    /** Set by tests; otherwise built from picocli's {@code getOut()} on first use. */
    Console console;

    /** Replaced in tests; reads one answer from the terminal. */
    Supplier<String> answerReader = () -> System.console().readLine();

    @Parameters(index = "0", paramLabel = "<jobId>", description = "The job to stop, as baas jobs list shows it.")
    String jobId;

    @Option(names = "--yes", description = "Skip the confirmation prompt. Required without a terminal.")
    boolean yes;

    @Override
    public Integer call() {
        BaasConfig config = BaasApp.configService(spec).load();
        RunCommand.operatorCredentialsWarning(config).ifPresent(logger::warn);
        var factory = new AwsClientFactory(config.getAws().resolveRegion(), config.getAws().resolveOperatorProfile());
        try (var dynamoDb = factory.dynamoDb();
             var quickDynamoDb = factory.dynamoDb(RunCommand.STOP_WRITE_TIMEOUT);
             var ec2 = factory.ec2()) {
            return new JobTermination(
                new DynamoDbJobRecorder(dynamoDb, config.resultsTable()),
                new DynamoDbJobRecorder(quickDynamoDb, config.resultsTable()),
                new Ec2ProvisioningService(ec2, config.stackName()))
                .terminate(jobId, confirmation());
        }
    }

    /**
     * Killing a running benchmark cannot be undone. On a terminal the operator confirms; without
     * one, only {@code --yes} proceeds — a script that forgot it must not hang on a prompt nobody
     * can answer, nor go ahead unasked.
     */
    BooleanSupplier confirmation() {
        return () -> {
            if (yes) {
                return true;
            }
            if (!console().interactive()) {
                logger.error("Refusing to terminate job {} without confirmation: no terminal to ask on. "
                    + "Pass --yes.", jobId);
                return false;
            }
            // Stays on stdout: the prompt has to sit on the cursor's line; see TeardownCommand.
            console().print("Terminate job " + jobId + "? [y/N] ");
            String answer = answerReader.get();
            return answer != null && ("y".equalsIgnoreCase(answer.strip()) || "yes".equalsIgnoreCase(answer.strip()));
        };
    }

    private Console console() {
        if (console == null) {
            console = Console.of(spec.commandLine().getOut());
        }
        return console;
    }
}
