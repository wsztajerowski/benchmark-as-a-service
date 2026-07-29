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
import pl.wsztajerowski.baas.infra.S3UploadService;
import pl.wsztajerowski.baas.infra.SsmService;

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

    @Option(names = "--mongo-uri", description = "MongoDB connection string (stored in SSM SecureString).")
    String mongoUri;

    private final ConfigService configService = new ConfigService();

    @Override
    public Integer call() throws Exception {
        // Fail before provisioning anything — a bad URI should not cost a stack deploy.
        if (mongoUri != null) {
            validateMongoUri(mongoUri);
        }

        BaasConfig config = configService.load();
        if (region != null) config.getAws().setRegion(region);
        if (awsProfile != null) config.getAws().setProfile(awsProfile);

        String resolvedRegion = config.getAws().getRegion();

        var factory = new AwsClientFactory(resolvedRegion, config.getAws().getProfile());

        String callerArn;
        try (var sts = factory.sts()) {
            callerArn = sts.getCallerIdentity().arn();
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

        String templateBody = loadTemplate();

        Map<String, String> params = new LinkedHashMap<>();
        params.put("ResourceNamePrefix", resolvedPrefix);
        params.put("UseExistingVpc", Boolean.toString(useExistingVpc));
        params.put("ExistingVpcId", existingVpcId != null ? existingVpcId : "");
        params.put("ExistingSubnetId", existingSubnetId != null ? existingSubnetId : "");
        params.put("ExistingSecurityGroupId", existingSecurityGroupId != null ? existingSecurityGroupId : "");

        // The bucket is declared DeletionPolicy: Retain, so deleting the stack leaves it
        // behind — and the prefix is a hash of the caller ARN, so the next setup asks for
        // that exact name again and CloudFormation refuses with an opaque "Validation failed
        // with 1 error(s)" that never mentions S3. Say what actually happened instead.
        try (var cf = factory.cloudFormation(); var s3 = factory.s3()) {
            String bucketName = "baas-" + resolvedPrefix;
            if (!new CloudFormationService(cf).stackExists(resolvedStack)
                && new S3UploadService(s3).bucketExists(bucketName)) {
                logger.error("""
                        Bucket {} already exists, but stack {} does not.
                          A previous teardown retained it — the stack cannot recreate a bucket
                          that is already there, and the name is fixed by your caller ARN.
                          Keep the old results:  aws s3 sync s3://{} ./backup
                          Then remove it:        aws s3 rb s3://{} --force""",
                    bucketName, resolvedStack, bucketName, bucketName);
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

        if (mongoUri != null) {
            try (var ssm = factory.ssm()) {
                new SsmService(ssm).putSecureParameter(
                    "/" + resolvedPrefix + "/mongo/connection-string", mongoUri);
                logger.info("MongoDB URI stored in SSM.");
            }
        } else {
            // warn, not info: with no URI the runner falls back to NoOpDatabaseService and every
            // measurement is silently discarded while the run still reports success.
            logger.warn("""
                No MongoDB connection string provided.
                Create a free Atlas cluster: https://www.mongodb.com/cloud/atlas/register
                Then run: baas config set --mongo-uri "mongodb+srv://<user>:<pass>@<host>/<db>"
                Remember to add the runner's egress IP and your laptop's IP to the Atlas IP Access List.""");
        }

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

    static void validateMongoUri(String uri) {
        var cs = new com.mongodb.ConnectionString(uri);
        if (cs.getDatabase() == null || cs.getDatabase().isEmpty()) {
            throw new IllegalArgumentException(
                "MongoDB URI must include a database name (e.g. mongodb+srv://user:pass@host/mydb)");
        }
    }
}
