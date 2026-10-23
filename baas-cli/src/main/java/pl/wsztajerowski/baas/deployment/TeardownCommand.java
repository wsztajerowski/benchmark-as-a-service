package pl.wsztajerowski.baas.deployment;

import pl.wsztajerowski.baas.cli.CommandRefusal;
import pl.wsztajerowski.baas.cli.Invocation;
import pl.wsztajerowski.baas.session.DeployerSession;
import pl.wsztajerowski.baas.session.CoreStack;
import pl.wsztajerowski.baas.session.DeploymentNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Option;
import pl.wsztajerowski.baas.cli.LoggingMixin;
import pl.wsztajerowski.baas.console.Hints;
import pl.wsztajerowski.baas.session.BaasConfig;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.jobs.Ec2ProvisioningService;
import pl.wsztajerowski.baas.image.RunnerImageExtension;
import pl.wsztajerowski.baas.image.RunnerImageParameters;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.EnumSet;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Command(
    name = "teardown",
    mixinStandardHelpOptions = true,
    description = "Delete the BaaS CloudFormation stack and associated resources."
)
public class TeardownCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(TeardownCommand.class);

    @Mixin LoggingMixin loggingMixin;

    @Spec CommandSpec spec;

    @Override
    public Integer call() {
        BaasConfig config = Invocation.configService(spec).load();

        // The named deployment, or the only one configured: load() refused anything ambiguous, so
        // with two deployments configured a teardown always names its target.
        String resolvedStack = resolveDeployment(config);

        int exit = removeDeployment(config, resolvedStack);
        if (exit != 0) {
            return exit;
        }

        // Last: the deployment no longer exists, so a file naming it would only be one more
        // deployment every later command has to disambiguate from. The last one included.
        Invocation.configService(spec).delete(resolvedStack);
        logger.info("Removed this machine's configuration for {}: {}", resolvedStack,
            Invocation.configService(spec).fileOf(resolvedStack));
        return 0;
    }

    /** The deployer's AWS calls. Replaced by tests with a recording fake. */
    Function<BaasConfig, TeardownAws> awsFor = config -> new AwsTeardownAws(DeployerSession.factory(config));

    /** The export the offer runs, under the same deployer credentials. Replaced by tests. */
    Function<BaasConfig, ExportAws> exportAwsFor = config -> new AwsExportAws(DeployerSession.factory(config));

    /**
     * Everything teardown does in AWS — the gates, the export it offers, the bucket, the stack and
     * the image — returning the command's exit code. The order is the point: each step runs only once
     * the ones whose failure must stop it have succeeded.
     */
    int removeDeployment(BaasConfig config, String resolvedStack) {
        // A terminal first: the export offer and the confirmation are both asked there, and no
        // option skips them, so a teardown without one would have nothing to go on.
        if (!console().interactive()) {
            logger.error("Refusing to tear down {}: teardown asks for confirmation on a terminal, and there is "
                + "none here. Nothing was deleted.", resolvedStack);
            return 1;
        }
        TeardownAws aws = awsFor.apply(config);

        // Gate 1: no active jobs
        var running = aws.liveRunners(resolvedStack);
        if (!running.isEmpty()) {
            throw CommandRefusal.refused(inFlightRefusal(resolvedStack, running));
        }

        // What goes, said before anything is asked: the extension's only copy is a stack parameter.
        boolean federated;
        boolean extension;
        try {
            Map<String, String> parameters = aws.stackParameters(resolvedStack);
            federated = !parameters.getOrDefault(CoreStack.PARAM_GITHUB_ORG, "").isBlank();
            extension = !RunnerImageExtension.parse(
                parameters.getOrDefault(RunnerImageParameters.EXTENSION_DATA, "")).content().isEmpty();
        } catch (RuntimeException e) {
            throw CommandRefusal.refused("Could not read the stack parameters of " + resolvedStack + " ("
                + e.getMessage() + "). Nothing was deleted.");
        }
        logger.warn("{}", everythingGoesNotice(resolvedStack, extension));

        // The export, before the confirmation: whoever confirms knows a complete bundle exists, and a
        // failed export never leaves a confirmed teardown half-done.
        offerExport(config, resolvedStack);

        // Gate 2: explicit confirmation
        if (!confirmed(resolvedStack)) {
            return 1;
        }

        // CloudFormation refuses to delete a non-empty bucket, so it is emptied first. A bucket that
        // cannot be emptied stops the teardown here, with the stack still whole, rather than
        // leaving a stack stuck in DELETE_FAILED.
        String bucket = DeploymentNames.of(resolvedStack).bucket();
        logger.info("Emptying S3 bucket: {}", bucket);
        try {
            aws.emptyBucket(bucket);
        } catch (RuntimeException e) {
            throw CommandRefusal.refused("Could not empty bucket " + bucket + " (" + e.getMessage()
                + "). The stack was not deleted; re-run teardown.");
        }

        // Delete stack
        aws.deleteStack(resolvedStack);

        // Then the image, which build-image created outside the stack. After the stack rather than
        // before: a failed stack deletion then leaves a deployment that still has an image to
        // run on. Never fatal — the stack is gone, so the teardown has succeeded either way.
        List<String> imageLeftovers = aws.retireImage(pointerPath(resolvedStack), recipeName(resolvedStack));
        if (imageLeftovers.isEmpty()) {
            logger.info("{}", imageRetiredNotice(resolvedStack));
        } else {
            logger.warn("{}", imageLeftoverNotice(resolvedStack, imageLeftovers));
        }

        if (federated) {
            logger.info("{}", providerLeftNotice(resolvedStack));
        }
        return 0;
    }

    /**
     * Asks whether to export first and, on a yes, runs the whole export into the directory the user
     * names. Teardown writes nothing locally except through this. A failed export stops the teardown.
     */
    void offerExport(BaasConfig config, String deployment) {
        console().print("Export " + deployment + " first, into a local bundle? [Y/n]: ");
        String answer = answerReader.get();
        if (answer == null || answer.strip().toLowerCase(java.util.Locale.ROOT).startsWith("n")) {
            return;
        }
        Instant now = Instant.now();
        Path defaultDirectory = DeploymentExport.defaultDirectory(deployment, now);
        console().print("Bundle directory [" + defaultDirectory + "]: ");
        String chosen = answerReader.get();
        Path directory = chosen == null || chosen.isBlank() ? defaultDirectory : Path.of(chosen.strip());
        try (ExportAws exportAws = exportAwsFor.apply(config)) {
            var outcome = new DeploymentExport(exportAws, deployment, config.getAws().resolveRegion(), () -> now)
                .run(directory, EnumSet.allOf(Part.class), false);
            ExportCommand.report(console(), deployment, outcome);
        } catch (IOException | RuntimeException e) {
            throw CommandRefusal.refused("The export of " + deployment + " failed: " + e.getMessage()
                + "\n  Nothing was deleted. Re-run teardown and decline the export, or fix the cause and export again.");
        }
    }

    /**
     * The GitHub OIDC provider is account-wide and may serve other projects, so no command deletes
     * it — setup creates it when absent and never modifies it. Said once its last known user here
     * is gone, so a leftover provider is never a surprise.
     */
    static String providerLeftNotice(String deployment) {
        return "The account's GitHub OIDC provider (token.actions.githubusercontent.com) is left in place: "
            + "it is account-wide and may serve other projects. " + deployment
            + " no longer trusts it, since its operator role is gone.";
    }

    /** Set by tests; otherwise built from picocli's {@code getOut()} on first use. */
    Console console;

    /** Replaced in tests; reads one answer from the terminal. */
    Supplier<String> answerReader = () -> System.console().readLine();

    /**
     * The stack name typed back on the terminal, the only confirmation there is. An abort exits 1,
     * so a script can tell it from a teardown done.
     */
    boolean confirmed(String stack) {
        // Stays on stdout: the prompt has to sit on the cursor's line, and every logger line
        // comes with a timestamp prefix and a newline. The Console flushes it.
        console().print("Type the deployment name to confirm deletion [" + stack + "]: ");
        String answer = answerReader.get();
        if (answer == null || !stack.equals(answer.strip())) {
            logger.info("Aborted. Nothing was deleted.");
            return false;
        }
        return true;
    }

    private Console console() {
        if (console == null) {
            console = Console.of(spec.commandLine().getOut());
        }
        return console;
    }

    /** The deployment being torn down: the one {@code --deployment} named, or the only one configured. */
    String resolveDeployment(BaasConfig config) {
        return config.requirePrefix();
    }

    /**
     * The deployment's AMI pointer, from the deployment being torn down.
     */
    static String pointerPath(String deployment) {
        return DeploymentNames.of(deployment).amiPointer();
    }

    /** The deployment's Image Builder recipe, whose image records teardown deletes. */
    static String recipeName(String deployment) {
        return DeploymentNames.of(deployment).runnerRecipe();
    }

    static String imageRetiredNotice(String deployment) {
        return """
            Runner image retired: the AMI %1$s named, its snapshots, the pointer itself and the
              Image Builder records of %2$s. A later setup of this deployment needs
              `%3$s` before `baas run` works.""".formatted(pointerPath(deployment), recipeName(deployment),
            Hints.command(deployment, "admin image build"));
    }

    static String imageLeftoverNotice(String deployment, List<String> leftovers) {
        return "Runner image of " + deployment + " only partly retired; the stack is deleted, so "
            + "remove these by hand:\n" + leftovers.stream()
                .map(line -> "  - " + line).collect(Collectors.joining("\n"));
    }

    /** Said before the prompt, so nobody confirms a teardown believing their history is kept. */
    static String everythingGoesNotice(String deployment, boolean extension) {
        return """
            Tearing down %1$s deletes everything it holds: the stack, the bucket %1$s and the
              results table %1$s-results, with every job and measurement in them, and the runner
              image%2$s. None of it can be recovered, except from an export.""".formatted(deployment,
            extension ? ", and the runner-image extension, whose only copy is in the stack" : "");
    }

    /**
     * Names each in-flight job by the {@code baas-job-id} tag its instance carries, read from
     * the same {@code DescribeInstances} the gate already makes: teardown runs with deployer
     * credentials, which hold no read of the results table, and need none for this.
     */
    static String inFlightRefusal(String deployment, List<Ec2ProvisioningService.LiveRunner> running) {
        String rows = running.stream()
            .map(r -> "  %-30s %-21s %s".formatted(
                r.jobId() == null ? "(no job id tag)" : r.jobId(), r.instanceId(), r.state()))
            .collect(Collectors.joining("\n"));
        return (running.size() == 1 ? "Aborting: 1 job is" : "Aborting: " + running.size() + " jobs are")
            + " still in flight:\n" + rows + "\n"
            + "Wait for them to finish, or stop each one:  " + Hints.command(deployment, "jobs terminate <jobId>");
    }
}
