package pl.wsztajerowski.baas.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.config.ConfigService;
import pl.wsztajerowski.baas.infra.AwsClientFactory;
import pl.wsztajerowski.baas.infra.CloudFormationService;
import pl.wsztajerowski.baas.infra.SsmService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
    name = "setup",
    mixinStandardHelpOptions = true,
    description = "Deploy AWS infrastructure (VPC, S3 bucket, IAM roles) via CloudFormation."
)
public class SetupCommand implements Callable<Integer> {

    @Option(names = "--prefix", description = "Resource name prefix (default: baas).")
    String prefix;

    @Option(names = "--region", description = "AWS region (default: eu-central-1).")
    String region;

    @Option(names = "--stack-name", description = "CloudFormation stack name (default: baas-main).")
    String stackName;

    @Option(names = "--aws-profile", description = "AWS CLI profile.")
    String awsProfile;

    @Option(names = "--github-org", description = "GitHub organisation name.", defaultValue = "wsztajerowski")
    String githubOrg;

    @Option(names = "--github-repo", description = "GitHub repository name.", defaultValue = "benchmark-as-a-service")
    String githubRepo;

    @Option(names = "--workflow-id", description = "GHA benchmark workflow ID.")
    String workflowId;

    @Option(names = "--workflow-branch", description = "GHA workflow branch.", defaultValue = "main")
    String workflowBranch;

    @Option(names = "--oidc-provider-arn", description = "Existing OIDC provider ARN (omit to create a new one).", defaultValue = "")
    String oidcProviderArn;

    @Option(names = "--use-existing-vpc", description = "Skip VPC/networking creation and use provided IDs.")
    boolean useExistingVpc;

    @Option(names = "--vpc-id", description = "Existing VPC ID (required with --use-existing-vpc).")
    String existingVpcId;

    @Option(names = "--subnet-id", description = "Existing subnet ID (required with --use-existing-vpc).")
    String existingSubnetId;

    @Option(names = "--sg-id", description = "Existing security group ID (required with --use-existing-vpc).")
    String existingSecurityGroupId;

    @Option(names = "--mongo-uri", description = "MongoDB connection string (stored in SSM SecureString).")
    String mongoUri;

    private final ConfigService configService = new ConfigService();

    @Override
    public Integer call() throws Exception {
        BaasConfig config = configService.load();
        if (prefix != null) config.setPrefix(prefix);
        if (region != null) config.getAws().setRegion(region);
        if (stackName != null) config.getAws().setStackName(stackName);
        if (awsProfile != null) config.getAws().setProfile(awsProfile);

        String resolvedPrefix = config.getPrefix();
        String resolvedRegion = config.getAws().getRegion();
        String resolvedStack = config.getAws().getStackName();

        if (useExistingVpc && (existingVpcId == null || existingSubnetId == null || existingSecurityGroupId == null)) {
            System.err.println("--use-existing-vpc requires --vpc-id, --subnet-id, and --sg-id.");
            return 1;
        }

        String templateBody = loadTemplate();

        Map<String, String> params = new LinkedHashMap<>();
        params.put("ResourceNamePrefix", resolvedPrefix);
        params.put("OIDCProviderArn", oidcProviderArn != null ? oidcProviderArn : "");
        params.put("GitHubOrg", githubOrg);
        params.put("GitHubRepo", githubRepo);
        params.put("GHABenchmarkWorkflowId", workflowId != null ? workflowId : "0");
        params.put("GHABenchmarkWorkflowBranch", workflowBranch);
        params.put("UseExistingVpc", useExistingVpc ? "true" : "false");
        params.put("ExistingVpcId", existingVpcId != null ? existingVpcId : "");
        params.put("ExistingSubnetId", existingSubnetId != null ? existingSubnetId : "");
        params.put("ExistingSecurityGroupId", existingSecurityGroupId != null ? existingSecurityGroupId : "");

        var factory = new AwsClientFactory(resolvedRegion, config.getAws().getProfile());

        try (var cf = factory.cloudFormation()) {
            new CloudFormationService(cf).createOrUpdateStack(resolvedStack, templateBody, params);
        }

        // Read CF outputs and write to config
        try (var cf = factory.cloudFormation()) {
            var outputs = new CloudFormationService(cf).getStackOutputs(resolvedStack);
            config.getAws().setBucket(outputs.getOrDefault("BucketName", ""));
            config.getAws().setSubnetId(outputs.getOrDefault("SubnetId", ""));
            config.getAws().setSecurityGroupId(outputs.getOrDefault("SecurityGroupId", ""));
            config.getAws().setVpcId(outputs.getOrDefault("VpcId", ""));
            config.getAws().setRunnerInstanceProfileName(outputs.getOrDefault("RunnerInstanceProfileName", ""));
        }

        configService.save(config);
        System.out.println("Configuration written to " + configService.configFilePath());

        if (mongoUri != null) {
            validateMongoUri(mongoUri);
            try (var ssm = factory.ssm()) {
                new SsmService(ssm).putSecureParameter(
                    "/" + resolvedPrefix + "/mongo/connection-string", mongoUri);
                System.out.println("MongoDB URI stored in SSM.");
            }
        } else {
            System.out.println();
            System.out.println("No MongoDB connection string provided.");
            System.out.println("Create a free Atlas cluster: https://www.mongodb.com/cloud/atlas/register");
            System.out.println("Then run: baas config set --mongo-uri \"mongodb+srv://<user>:<pass>@<host>/<db>\"");
            System.out.println("Remember to add the runner's egress IP and your laptop's IP to the Atlas IP Access List.");
        }

        return 0;
    }

    private String loadTemplate() throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/templates/cf-template-main.yaml")) {
            if (is == null) throw new IllegalStateException("CF template not found in classpath");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void validateMongoUri(String uri) {
        var cs = new com.mongodb.ConnectionString(uri);
        if (cs.getDatabase() == null || cs.getDatabase().isEmpty()) {
            throw new IllegalArgumentException(
                "MongoDB URI must include a database name (e.g. mongodb+srv://user:pass@host/mydb)");
        }
    }
}
