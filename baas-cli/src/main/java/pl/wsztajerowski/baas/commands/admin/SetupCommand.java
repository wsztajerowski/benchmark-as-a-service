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
import pl.wsztajerowski.baas.infra.DeployerPolicyRenderer;
import pl.wsztajerowski.baas.infra.DeployerPreflight;
import pl.wsztajerowski.baas.infra.RunnerImageRenderer;
import pl.wsztajerowski.baas.infra.ResultsTableService;
import pl.wsztajerowski.baas.infra.S3UploadService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
    name = "setup",
    mixinStandardHelpOptions = true,
    description = "Deploy AWS infrastructure (VPC, S3 bucket, IAM roles) via CloudFormation."
)
public class SetupCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(SetupCommand.class);

    @Mixin LoggingMixin loggingMixin;

    @Option(names = "--region", description = "AWS region (default: eu-central-1).")
    String region;

    @Option(names = "--aws-profile", description = "AWS CLI profile.")
    String awsProfile;

    @Option(names = "--use-existing-vpc", description = "Skip VPC/networking creation and use provided IDs.")
    boolean useExistingVpc;

    @Option(names = "--vpc-id", description = "Existing VPC ID (required with --use-existing-vpc).")
    String existingVpcId;

    @Option(names = "--subnet-id", description = "Existing subnet ID (required with --use-existing-vpc).")
    String existingSubnetId;

    @Option(names = "--sg-id", description = "Existing security group ID (required with --use-existing-vpc).")
    String existingSecurityGroupId;

    private final ConfigService configService = new ConfigService();

    @Override
    public Integer call() throws Exception {
        BaasConfig config = configService.load();
        if (region != null) config.getAws().setRegion(region);
        if (awsProfile != null) config.getAws().setProfile(awsProfile);

        String resolvedRegion = config.getAws().getRegion();

        var factory = new AwsClientFactory(resolvedRegion, config.getAws().getProfile());

        String callerArn;
        String accountId;
        try (var sts = factory.sts()) {
            var identity = sts.getCallerIdentity();
            callerArn = identity.arn();
            accountId = identity.account();
        }
        logger.debug("Caller ARN: {}", callerArn);
        String resolvedPrefix = computePrefix(callerArn);
        String resolvedStack = "baas-" + resolvedPrefix;

        config.setPrefix(resolvedPrefix);
        config.getAws().setCoreStackName(resolvedStack);

        logger.info("Using prefix: {} (derived from caller ARN)", resolvedPrefix);

        if (useExistingVpc && (existingVpcId == null || existingSubnetId == null || existingSecurityGroupId == null)) {
            logger.error("--use-existing-vpc requires --vpc-id, --subnet-id, and --sg-id.");
            return 1;
        }

        try {
            preflight(factory, callerArn, accountId, resolvedRegion, resolvedPrefix);
        } catch (IllegalStateException e) {
            logger.error(e.getMessage());
            return 1;
        }

        try {
            return deploy(factory, config, resolvedPrefix, resolvedStack);
        } catch (RuntimeException e) {
            if (!DeployerPreflight.isAccessDenied(e)) {
                throw e;
            }
            // The SDK names the action but never what to do about it. The rendered policy is the
            // answer, and it is caller-specific — there is no generic version to link to.
            logger.error("""
                {}

                This identity is missing a permission `baas admin setup` needs. Attach the policy
                below (rendered for account {}, region {}, prefix {}):

                {}""",
                e.getMessage(), accountId, resolvedRegion, resolvedPrefix,
                new DeployerPolicyRenderer().render(accountId, resolvedRegion, resolvedPrefix));
            return 1;
        }
    }

    private void preflight(AwsClientFactory factory, String callerArn, String accountId,
                           String resolvedRegion, String resolvedPrefix) {
        var renderer = new DeployerPolicyRenderer();
        try (var iam = factory.iam()) {
            var denied = new DeployerPreflight(iam)
                .simulateCriticalActions(callerArn, accountId, resolvedRegion, resolvedPrefix);
            if (!denied.isEmpty()) {
                throw new IllegalStateException("""
                    This identity cannot %s.

                    Attach the policy below (rendered for account %s, region %s, prefix %s):

                    %s"""
                    .formatted(String.join(", ", denied), accountId, resolvedRegion, resolvedPrefix,
                        renderer.render(accountId, resolvedRegion, resolvedPrefix)));
            }
        }
    }

    private Integer deploy(AwsClientFactory factory, BaasConfig config, String resolvedPrefix,
                           String resolvedStack) throws Exception {
        String templateBody = loadTemplate();

        Map<String, String> params = new LinkedHashMap<>();
        params.put("ResourceNamePrefix", resolvedPrefix);
        params.put("UseExistingVpc", Boolean.toString(useExistingVpc));
        params.put("ExistingVpcId", existingVpcId != null ? existingVpcId : "");
        params.put("ExistingSubnetId", existingSubnetId != null ? existingSubnetId : "");
        params.put("ExistingSecurityGroupId", existingSecurityGroupId != null ? existingSecurityGroupId : "");
        // The same rendering `baas admin build-image` submits. Letting the template's placeholder
        // default stand here would register a no-op component at the declared version, and Image
        // Builder would then refuse the real one at that same version — immutability, hit from a
        // direction nobody would think to look.
        params.putAll(new RunnerImageRenderer().stackParameters());

        // The bucket and the results table are both declared DeletionPolicy: Retain, so deleting
        // the stack leaves them behind — and the prefix is a hash of the caller ARN, so the next
        // setup asks for those exact names again and CloudFormation refuses with an opaque
        // "Validation failed with 1 error(s)" that never mentions which resource. Say what
        // actually happened instead. Both are checked, because fixing only the bucket then fails
        // again on the table with the same unhelpful message.
        try (var cf = factory.cloudFormation(); var s3 = factory.s3(); var ddb = factory.dynamoDb()) {
            String bucketName = "baas-" + resolvedPrefix;
            String tableName = "baas-" + resolvedPrefix + "-results";
            boolean stackMissing = !new CloudFormationService(cf).stackExists(resolvedStack);

            if (stackMissing && new S3UploadService(s3).bucketExists(bucketName)) {
                logger.error("""
                        Bucket {} already exists, but stack {} does not.
                          A previous teardown retained it — the stack cannot recreate a bucket
                          that is already there, and the name is fixed by your caller ARN.
                          Keep the old results:  aws s3 sync s3://{} ./backup
                          Then remove it:        aws s3 rb s3://{} --force""",
                    bucketName, resolvedStack, bucketName, bucketName);
                return 1;
            }

            if (stackMissing && new ResultsTableService(ddb).tableExists(tableName)) {
                logger.error("""
                        Results table {} already exists, but stack {} does not.
                          A previous teardown retained it, for the same reason the bucket is
                          retained: benchmark history outlives any single stack.
                          Keep the old results:  aws dynamodb scan --table-name {} > backup.json
                          Then remove it:        aws dynamodb delete-table --table-name {}""",
                    tableName, resolvedStack, tableName, tableName);
                return 1;
            }
        }

        try (var cf = factory.cloudFormation()) {
            new CloudFormationService(cf).createOrUpdateStack(resolvedStack, templateBody, params);
        }

        // Read CF outputs and write to config
        String operatorRoleArn;
        try (var cf = factory.cloudFormation()) {
            var outputs = new CloudFormationService(cf).getStackOutputs(resolvedStack);
            config.getAws().setBucket(outputs.getOrDefault("BucketName", ""));
            config.getAws().setSubnetId(outputs.getOrDefault("SubnetId", ""));
            config.getAws().setSecurityGroupId(outputs.getOrDefault("SecurityGroupId", ""));
            config.getAws().setVpcId(outputs.getOrDefault("VpcId", ""));
            config.getAws().setRunnerInstanceProfileName(outputs.getOrDefault("RunnerInstanceProfileName", ""));
            config.getAws().setResultsTable(outputs.getOrDefault("ResultsTableName", ""));
            operatorRoleArn = outputs.getOrDefault("OperatorRoleArn", "");
        }

        configService.save(config);
        logger.info("Configuration written to {}", configService.configFilePath());

        if (!operatorRoleArn.isEmpty()) {
            logger.info("""
                    BaasCliOperatorRole created: {}
                    Nobody can assume it yet. Two one-time steps:
                      1. Grant sts:AssumeRole on this ARN to the IAM user who runs benchmarks,
                         and add a ~/.aws/config profile with role_arn + source_profile. See infra/README.md.
                      2. Point the CLI at that profile:
                           baas config set --operator-profile <profile-name>
                         Until you do, `baas run` uses the default credential chain, not this role.""",
                operatorRoleArn);
        }

        // Setup deliberately does not build the image — that is a ~15-minute operation and every
        // re-setup would pay for it. It is a hard precondition of `baas run`, so say so here
        // rather than letting the first run be where the user finds out.
        logger.info("""
                Next: build the runner image.
                      baas admin build-image
                    Takes ~15 minutes and publishes an AMI to /{}/runner/ami-id.
                    `baas run` fails until it exists — there is no boot-time install path.""",
            resolvedPrefix);

        return 0;
    }

    /**
     * Derives a short, deterministic, lowercase prefix from the caller's ARN:
     * {@code prefix = lowercase(base32(sha256(arn)))[0:8]}
     */
    static String computePrefix(String arn) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(arn.getBytes(StandardCharsets.UTF_8));
        return base32Encode(hash).substring(0, 8).toLowerCase();
    }

    /**
     * RFC 4648 Base32 encoding (no padding).
     */
    private static String base32Encode(byte[] data) {
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                bitsLeft -= 5;
                sb.append(alphabet.charAt((buffer >> bitsLeft) & 0x1F));
            }
        }
        if (bitsLeft > 0) {
            buffer <<= (5 - bitsLeft);
            sb.append(alphabet.charAt(buffer & 0x1F));
        }
        return sb.toString();
    }

    private String loadTemplate() throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/templates/cf-template-core.yaml")) {
            if (is == null) throw new IllegalStateException("CF template not found in classpath");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
