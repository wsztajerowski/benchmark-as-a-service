package pl.wsztajerowski.baas.commands.admin;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
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

@Command(
    name = "teardown",
    mixinStandardHelpOptions = true,
    description = "Delete the BaaS CloudFormation stack and associated resources."
)
public class TeardownCommand implements Callable<Integer> {

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
                System.err.println("Aborting: active benchmark runner instances detected:");
                running.forEach(id -> System.err.println("  " + id));
                System.err.println("Wait for them to finish or terminate them manually before tearing down.");
                return 1;
            }
        }

        // Gate 2: explicit confirmation
        if (!yes) {
            System.out.print("Type the stack name to confirm deletion [" + resolvedStack + "]: ");
            String input = new Scanner(System.in).nextLine().trim();
            if (!resolvedStack.equals(input)) {
                System.out.println("Aborted.");
                return 0;
            }
        }

        // Empty + delete S3 bucket if requested. The stack declares DeletionPolicy: Retain,
        // so CloudFormation will not remove the bucket — teardown has to do it here.
        boolean bucketDeleted = false;
        if (deleteBucket && config.getAws().getBucket() != null) {
            String bucket = config.getAws().getBucket();
            System.out.println("Emptying S3 bucket: " + bucket);
            try (var s3 = factory.s3()) {
                var s3Service = new S3UploadService(s3);
                s3Service.deleteAllObjects(bucket);
                s3Service.deleteBucket(bucket);
                bucketDeleted = true;
                System.out.println("Deleted S3 bucket: " + bucket);
            } catch (RuntimeException e) {
                // Don't abort the teardown — leaving the stack behind is worse than
                // leaving the bucket behind, and the bucket is recoverable by hand.
                System.err.println("Warning: could not delete bucket " + bucket + ": " + e.getMessage());
                System.err.println("  Continuing with stack deletion; remove the bucket manually.");
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
            System.out.println("Deleted SSM parameter: " + paramName);
        }

        System.out.println();
        if (!bucketDeleted && config.getAws().getBucket() != null) {
            System.out.println("S3 results bucket retained: " + config.getAws().getBucket());
            System.out.println("  Note: `baas admin setup` as this identity will fail while it exists —");
            System.out.println("  the bucket name comes from a hash of your caller ARN, so the next setup");
            System.out.println("  tries to create this same name. Delete it manually, or re-run teardown");
            System.out.println("  with --delete-bucket.");
        }
        System.out.println("MongoDB cluster NOT touched (connect-only; delete it manually in Atlas if desired).");
        return 0;
    }
}
