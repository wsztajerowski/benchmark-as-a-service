package pl.wsztajerowski.baas.jobs;

import pl.wsztajerowski.baas.cli.CommandContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import pl.wsztajerowski.baas.cli.LoggingMixin;
import pl.wsztajerowski.baas.session.BaasConfig;

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

    @Mixin CommandContext context = new CommandContext();

    /** Replaced in tests; reads one answer from the terminal. */
    Supplier<String> answerReader = () -> System.console().readLine();

    @Parameters(index = "0", paramLabel = "<jobId>", description = "The job to stop, as baas jobs list shows it.")
    String jobId;

    @Option(names = "--yes", description = "Skip the confirmation prompt. Required without a terminal.")
    boolean yes;

    @Override
    public Integer call() {
        BaasConfig config = context.operator().config();
        // Unchecked against the deployment's recorded release: stopping a paid instance never
        // waits on an install, and an older CLI's stop step does no harm.
        var factory = context.operator().uncheckedFactory();
        try (var dynamoDb = factory.dynamoDb();
             var quickDynamoDb = factory.dynamoDb(pl.wsztajerowski.baas.jobs.JobSession.STOP_WRITE_TIMEOUT);
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
            if (!context.console().interactive()) {
                logger.error("Refusing to terminate job {} without confirmation: no terminal to ask on. "
                    + "Pass --yes.", jobId);
                return false;
            }
            // Stays on stdout: the prompt has to sit on the cursor's line; see TeardownCommand.
            context.console().print("Terminate job " + jobId + "? [y/N] ");
            String answer = answerReader.get();
            return answer != null && ("y".equalsIgnoreCase(answer.strip()) || "yes".equalsIgnoreCase(answer.strip()));
        };
    }

}
