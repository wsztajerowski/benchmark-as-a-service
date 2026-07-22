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

        // Empty + delete S3 bucket if requested
        if (deleteBucket && config.getAws().getBucket() != null) {
            System.out.println("Emptying S3 bucket: " + config.getAws().getBucket());
            try (var s3 = factory.s3()) {
                new S3UploadService(s3).deleteAllObjects(config.getAws().getBucket());
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
        if (!deleteBucket) {
            System.out.println("S3 results bucket retained: " + config.getAws().getBucket());
            System.out.println("  Delete manually if no longer needed, or re-run with --delete-bucket.");
        }
        System.out.println("MongoDB cluster NOT touched (connect-only; delete it manually in Atlas if desired).");
        return 0;
    }
}
