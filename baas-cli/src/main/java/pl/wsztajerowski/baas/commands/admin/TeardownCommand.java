package pl.wsztajerowski.baas.commands.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import pl.wsztajerowski.baas.LoggingMixin;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.config.ConfigService;
import pl.wsztajerowski.baas.infra.AwsClientFactory;
import pl.wsztajerowski.baas.infra.CloudFormationService;
import pl.wsztajerowski.baas.infra.Ec2ProvisioningService;
import pl.wsztajerowski.baas.infra.S3UploadService;
import pl.wsztajerowski.baas.infra.SsmService;

import java.util.List;
import java.util.Scanner;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Command(
    name = "teardown",
    mixinStandardHelpOptions = true,
    description = "Delete the BaaS CloudFormation stack and associated resources."
)
public class TeardownCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(TeardownCommand.class);

    @Mixin LoggingMixin loggingMixin;

    @Option(names = "--stack-name", description = "CloudFormation stack name to delete.")
    String stackName;

    @Option(names = "--yes", description = "Skip interactive confirmation.")
    boolean yes;

    @Option(names = "--delete-bucket", description = "Empty and delete the S3 results bucket (default: retain).")
    boolean deleteBucket;

    private final ConfigService configService = new ConfigService();

    @Override
    public Integer call() {
        BaasConfig config = configService.load();
        String resolvedStack = stackName != null ? stackName : config.getAws().getCoreStackName();

        var factory = new AwsClientFactory(config.getAws().getRegion(), config.getAws().getProfile());

        // Gate 1: no active runs
        try (var ec2 = factory.ec2()) {
            List<String> running = new Ec2ProvisioningService(ec2).listRunningBenchmarkInstances();
            if (!running.isEmpty()) {
                logger.error("Aborting: active benchmark runner instances detected:\n{}\n"
                        + "Wait for them to finish or terminate them manually before tearing down.",
                    running.stream().map(id -> "  " + id).collect(Collectors.joining("\n")));
                return 1;
            }
        }

        // Gate 2: explicit confirmation
        if (!yes) {
            // Stays on stdout: an interactive prompt needs to sit on the same line as the
            // cursor, and every logger line comes with a timestamp prefix and a newline.
            System.out.print("Type the stack name to confirm deletion [" + resolvedStack + "]: ");
            String input = new Scanner(System.in).nextLine().trim();
            if (!resolvedStack.equals(input)) {
                logger.info("Aborted.");
                return 0;
            }
        }

        // Empty + delete S3 bucket if requested. The stack declares DeletionPolicy: Retain,
        // so CloudFormation will not remove the bucket — teardown has to do it here.
        boolean bucketDeleted = false;
        if (deleteBucket && config.getAws().getBucket() != null) {
            String bucket = config.getAws().getBucket();
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

        // Delete Mongo SSM SecureString
        try (var ssm = factory.ssm()) {
            String paramName = "/" + config.getPrefix() + "/mongo/connection-string";
            new SsmService(ssm).deleteParameter(paramName);
            logger.info("Deleted SSM parameter: {}", paramName);
        }

        // Both retained resources are named, because a setup that trips over one and then the
        // other is two rounds of the same opaque CloudFormation error.
        if (!bucketDeleted && config.getAws().getBucket() != null) {
            logger.warn("""
                    S3 results bucket retained: {}
                      Note: `baas admin setup` as this identity will fail while it exists —
                      the bucket name comes from a hash of your caller ARN, so the next setup
                      tries to create this same name. Delete it manually, or re-run teardown
                      with --delete-bucket.""",
                config.getAws().getBucket());
        }
        if (config.getAws().getResultsTable() != null && !config.getAws().getResultsTable().isBlank()) {
            logger.warn("""
                    DynamoDB results table retained: {}
                      Benchmark history outlives the stack, so teardown never deletes it and
                      there is no flag to. `baas admin setup` as this identity will fail while
                      it exists; remove it with:
                        aws dynamodb delete-table --table-name {}""",
                config.getAws().getResultsTable(), config.getAws().getResultsTable());
        }
        logger.info("MongoDB cluster NOT touched (connect-only; delete it manually in Atlas if desired).");
        return 0;
    }
}
