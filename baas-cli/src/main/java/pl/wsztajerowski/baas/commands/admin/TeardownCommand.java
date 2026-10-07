package pl.wsztajerowski.baas.commands.admin;

import pl.wsztajerowski.baas.config.DeploymentNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Option;
import pl.wsztajerowski.baas.BaasApp;
import pl.wsztajerowski.baas.LoggingMixin;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.config.ConfigService;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.infra.AwsClientFactory;
import pl.wsztajerowski.baas.infra.CloudFormationService;
import pl.wsztajerowski.baas.infra.Ec2ProvisioningService;
import pl.wsztajerowski.baas.infra.ImageBuilderService;
import pl.wsztajerowski.baas.infra.RunnerImageExtension;
import pl.wsztajerowski.baas.infra.RunnerImageParameters;
import pl.wsztajerowski.baas.infra.S3UploadService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
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

    @Option(names = "--yes", description = "Skip interactive confirmation.")
    boolean yes;


    private ConfigService configService() {
        return BaasApp.configService(spec);
    }

    @Override
    public Integer call() {
        BaasConfig config = configService().load();

        var factory = new AwsClientFactory(config.getAws().resolveRegion(), config.getAws().getProfile());

        // The configured deployment; a --deployment naming another one was refused before this ran.
        String resolvedStack = resolveDeployment(config);

        // Gate 1: no active jobs
        try (var ec2 = factory.ec2()) {
            var running = new Ec2ProvisioningService(ec2).listRunningBenchmarkInstances();
            if (!running.isEmpty()) {
                logger.error(inFlightRefusal(running));
                return 1;
            }
        }

        // Gate 2: explicit confirmation, after saying what goes: nothing survives a teardown.
        logger.warn("{}", everythingGoesNotice(resolvedStack));
        if (!confirmed(resolvedStack)) {
            return 1;
        }

        // Before anything is deleted: the extension exists only as a stack parameter, so deleting
        // the stack deletes the only copy. A teardown that cannot save it deletes nothing — a
        // re-run costs a minute, a lost extension cannot be recovered.
        Optional<Path> savedExtension;
        try (var cf = factory.cloudFormation()) {
            String extension = new CloudFormationService(cf).getStackParameters(resolvedStack)
                .getOrDefault(RunnerImageParameters.EXTENSION_DATA, "");
            savedExtension = saveExtension(extension,
                configService().configFilePath().toAbsolutePath().getParent(), resolvedStack);
        } catch (IOException | RuntimeException e) {
            logger.error("Could not save the runner-image extension of {} ({}). Nothing was deleted.\n"
                + "  Save it by hand with `baas admin image show --extension > <file>`, then re-run teardown.",
                resolvedStack, e.getMessage());
            return 1;
        }

        // CloudFormation refuses to delete a non-empty bucket, so it is emptied first. A bucket that
        // cannot be emptied stops the teardown here, with the stack still whole, rather than
        // leaving a stack stuck in DELETE_FAILED.
        String bucket = resolvedStack;
        logger.info("Emptying S3 bucket: {}", bucket);
        try (var s3 = factory.s3()) {
            new S3UploadService(s3).deleteAllObjects(bucket);
        } catch (RuntimeException e) {
            logger.error("Could not empty bucket {} ({}). The stack was not deleted; re-run teardown.",
                bucket, e.getMessage());
            return 1;
        }

        // Delete stack
        try (var cf = factory.cloudFormation()) {
            new CloudFormationService(cf).deleteStack(resolvedStack);
        }

        // Then the image, which build-image created outside the stack. After the stack rather than
        // before: a failed stack deletion then leaves a deployment that still has an image to
        // run on. Never fatal — the stack is gone, so the teardown has succeeded either way.
        List<String> imageLeftovers;
        try (var imageBuilder = factory.imageBuilder(); var ec2 = factory.ec2(); var ssm = factory.ssm()) {
            imageLeftovers = new ImageBuilderService(imageBuilder, ec2, ssm)
                .retireDeployment(pointerPath(resolvedStack), recipeName(resolvedStack));
        }
        if (imageLeftovers.isEmpty()) {
            logger.info("{}", imageRetiredNotice(resolvedStack));
        } else {
            logger.warn("{}", imageLeftoverNotice(resolvedStack, imageLeftovers));
        }

        savedExtension.ifPresent(file -> logger.warn("{}", extensionSavedNotice(file)));
        return 0;
    }

    /**
     * Writes a non-empty extension, with the marker a pull prints, to
     * {@code runner-image-extension.<deployment>.yaml} in {@code directory}; writes nothing for
     * a deployment without one. A later setup's stack holds no extension, so
     * {@code build-image --extension} accepts the file as it is.
     */
    static Optional<Path> saveExtension(String extension, Path directory, String deployment) throws IOException {
        if (extension == null || extension.isBlank()) {
            return Optional.empty();
        }
        Files.createDirectories(directory);
        Path file = directory.resolve("runner-image-extension." + deployment + ".yaml");
        Files.writeString(file, RunnerImageExtension.withMarker(extension));
        return Optional.of(file);
    }

    static String extensionSavedNotice(Path file) {
        return """
            Runner-image extension saved to %1$s
              The stack held its only copy. After a later setup, push it back with:
                baas admin image build --extension %1$s""".formatted(file);
    }

    /** Set by tests; otherwise built from picocli's {@code getOut()} on first use. */
    Console console;

    /** Replaced in tests; reads one answer from the terminal. */
    Supplier<String> answerReader = () -> System.console().readLine();

    /**
     * {@code --yes}, or the stack name typed back on a terminal. Without a terminal only
     * {@code --yes} proceeds, as for {@code baas jobs terminate}: reading a closed stdin used to
     * crash with "No line found". An abort exits 1, so a script can tell it from a teardown done.
     */
    boolean confirmed(String stack) {
        if (yes) {
            return true;
        }
        if (!console().interactive()) {
            logger.error("Refusing to tear down {} without confirmation: no terminal to ask on. "
                + "Pass --yes. Nothing was deleted.", stack);
            return false;
        }
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

    /** This machine's configured deployment — the only one {@code --deployment} may name. */
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
              `baas admin image build` before `baas run` works.""".formatted(pointerPath(deployment), recipeName(deployment));
    }

    static String imageLeftoverNotice(String deployment, List<String> leftovers) {
        return "Runner image of " + deployment + " only partly retired; the stack is deleted, so "
            + "remove these by hand:\n" + leftovers.stream()
                .map(line -> "  - " + line).collect(Collectors.joining("\n"));
    }

    /** Said before the prompt, so nobody confirms a teardown believing their history is kept. */
    static String everythingGoesNotice(String deployment) {
        return """
            Tearing down %1$s deletes everything it holds: the stack, the bucket %1$s and the
              results table %1$s-results, with every job and measurement in them, and the runner
              image. None of it can be recovered.""".formatted(deployment);
    }

    /**
     * Names each in-flight job by the {@code baas-job-id} tag its instance carries, read from
     * the same {@code DescribeInstances} the gate already makes: teardown runs with deployer
     * credentials, which hold no read of the results table, and need none for this.
     */
    static String inFlightRefusal(List<Ec2ProvisioningService.LiveRunner> running) {
        String rows = running.stream()
            .map(r -> "  %-30s %-21s %s".formatted(
                r.jobId() == null ? "(no job id tag)" : r.jobId(), r.instanceId(), r.state()))
            .collect(Collectors.joining("\n"));
        return (running.size() == 1 ? "Aborting: 1 job is" : "Aborting: " + running.size() + " jobs are")
            + " still in flight:\n" + rows + "\n"
            + "Wait for them to finish, or stop each one:  baas jobs terminate <jobId>";
    }
}
