package pl.wsztajerowski.baas.commands.admin;

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

    @Option(names = "--stack-name",
        description = "Installation to delete, as printed by `baas admin setup` "
            + "(e.g. baas-123456789012). Defaults to this machine's configured installation.")
    String stackName;

    @Option(names = "--yes", description = "Skip interactive confirmation.")
    boolean yes;

    @Option(names = "--delete-bucket", description = "Empty and delete the S3 results bucket (default: retain).")
    boolean deleteBucket;

    private ConfigService configService() {
        return BaasApp.configService(spec);
    }

    @Override
    public Integer call() {
        BaasConfig config = configService().load();

        var factory = new AwsClientFactory(config.getAws().resolveRegion(), config.getAws().getProfile());

        // An explicit --stack-name still wins: it is how a by-hand installation, or one deployed
        // under the old caller-ARN naming, is reached.
        String resolvedStack = resolveInstallation(config);

        // Gate 1: no active jobs
        try (var ec2 = factory.ec2()) {
            var running = new Ec2ProvisioningService(ec2).listRunningBenchmarkInstances();
            if (!running.isEmpty()) {
                logger.error(inFlightRefusal(running));
                return 1;
            }
        }

        // Gate 2: explicit confirmation
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
                + "  Save it by hand with `baas admin image --extension > <file>`, then re-run teardown.",
                resolvedStack, e.getMessage());
            return 1;
        }

        // Empty + delete S3 bucket if requested. The stack declares DeletionPolicy: Retain,
        // so CloudFormation will not remove the bucket — teardown has to do it here.
        // Derived from the installation being torn down, not from config: --stack-name may name
        // a different installation than this machine is configured for.
        String bucket = resolvedStack;
        String resultsTable = resolvedStack + "-results";

        boolean bucketDeleted = false;
        if (deleteBucket) {
            logger.info("Emptying S3 bucket: {}", bucket);
            try (var s3 = factory.s3()) {
                var s3Service = new S3UploadService(s3);
                s3Service.deleteAllObjects(bucket);
                s3Service.deleteBucket(bucket);
                bucketDeleted = true;
                logger.info("Deleted S3 bucket: {}", bucket);
            } catch (RuntimeException e) {
                // Don't abort the teardown — leaving the stack behind is worse than
                // leaving the bucket behind, and the bucket is recoverable by hand.
                logger.warn("Could not delete bucket {}: {}\n"
                    + "  Continuing with stack deletion; remove the bucket manually.", bucket, e.getMessage());
            }
        }

        // Delete stack
        try (var cf = factory.cloudFormation()) {
            new CloudFormationService(cf).deleteStack(resolvedStack);
        }

        // Then the image, which build-image created outside the stack. After the stack rather than
        // before: a failed stack deletion then leaves an installation that still has an image to
        // run on. Never fatal — the stack is gone, so the teardown has succeeded either way.
        List<String> imageLeftovers;
        try (var imageBuilder = factory.imageBuilder(); var ec2 = factory.ec2(); var ssm = factory.ssm()) {
            imageLeftovers = new ImageBuilderService(imageBuilder, ec2, ssm)
                .retireInstallation(pointerPath(resolvedStack), recipeName(resolvedStack));
        }
        if (imageLeftovers.isEmpty()) {
            logger.info("{}", imageRetiredNotice(resolvedStack));
        } else {
            logger.warn("{}", imageLeftoverNotice(resolvedStack, imageLeftovers));
        }

        // Both retained resources are named, because a setup that trips over one and then the
        // other is two rounds of the same opaque CloudFormation error.
        if (!bucketDeleted) {
            logger.warn("""
                    S3 results bucket retained: {}
                      The name is derived from this AWS account, so any later `baas admin setup`
                      for the same installation asks for this same bucket and fails while it
                      exists — whoever runs it, not just you. Keep the results by copying them
                      out, then delete it manually or re-run teardown with --delete-bucket.""",
                bucket);
        }
        logger.warn("{}", retainedTableNotice(resultsTable, configService().configFilePath()));
        savedExtension.ifPresent(file -> logger.warn("{}", extensionSavedNotice(file)));
        return 0;
    }

    /**
     * Writes a non-empty extension, with the marker a pull prints, to
     * {@code runner-image-extension.<installation>.yaml} in {@code directory}; writes nothing for
     * an installation without one. A later setup's stack holds no extension, so
     * {@code build-image --extension} accepts the file as it is.
     */
    static Optional<Path> saveExtension(String extension, Path directory, String installation) throws IOException {
        if (extension == null || extension.isBlank()) {
            return Optional.empty();
        }
        Files.createDirectories(directory);
        Path file = directory.resolve("runner-image-extension." + installation + ".yaml");
        Files.writeString(file, RunnerImageExtension.withMarker(extension));
        return Optional.of(file);
    }

    static String extensionSavedNotice(Path file) {
        return """
            Runner-image extension saved to %1$s
              The stack held its only copy. After a later setup, push it back with:
                baas admin build-image --extension %1$s""".formatted(file);
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
        console().print("Type the stack name to confirm deletion [" + stack + "]: ");
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

    /** {@code --stack-name} when given, otherwise this machine's configured installation. */
    String resolveInstallation(BaasConfig config) {
        return stackName != null ? stackName : config.requirePrefix();
    }

    /**
     * The installation's AMI pointer. From the installation being torn down, not this machine's
     * configured prefix: {@code --stack-name} may name another installation, and retiring the
     * configured one's image instead would break an installation nobody asked to touch.
     */
    static String pointerPath(String installation) {
        return "/" + installation + "/runner/ami-id";
    }

    /** The installation's Image Builder recipe, whose image records teardown deletes. */
    static String recipeName(String installation) {
        return installation + "-recipe-runner";
    }

    static String imageRetiredNotice(String installation) {
        return """
            Runner image retired: the AMI %1$s named, its snapshots, the pointer itself and the
              Image Builder records of %2$s. A later setup of this installation needs
              `baas admin build-image` before `baas run` works.""".formatted(pointerPath(installation), recipeName(installation));
    }

    static String imageLeftoverNotice(String installation, List<String> leftovers) {
        return "Runner image of " + installation + " only partly retired; the stack is deleted, so "
            + "remove these by hand:\n" + leftovers.stream()
                .map(line -> "  - " + line).collect(Collectors.joining("\n"));
    }

    /**
     * Teardown leaves the configuration file alone, so it still names the installation and a plain
     * {@code baas results} keeps reading the retained table. Elsewhere, the same file reaches it
     * through {@code --config-path} — the only way to address another installation now that the
     * per-command {@code --results-table} override is gone.
     */
    static String retainedTableNotice(String resultsTable, Path configFile) {
        return """
            DynamoDB results table retained: %1$s
              Benchmark history outlives the stack, so teardown never deletes it and there is
              no flag to. The name is derived from this AWS account, so a later
              `baas admin setup` for the same installation will fail while it exists.
              Read it any time with:  baas results --all-projects
              %2$s still names it; keep a copy to read it elsewhere with --config-path.
              Remove it with:         aws dynamodb delete-table --table-name %1$s""".formatted(resultsTable, configFile);
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
