package pl.wsztajerowski.baas.infra;

import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.config.ConfigService;
import pl.wsztajerowski.baas.config.DeploymentNames;
import pl.wsztajerowski.baas.config.DeploymentSelectionException;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * How a day-to-day command reaches its deployment: which deployment, as which identity, in which
 * region. Every command outside {@code baas admin} goes through here, so none of them can run as
 * anything but the deployment's operator role.
 *
 * <pre>
 *  source   = aws.operatorProfile ?: aws.deployerProfile ?: the default credential chain
 *  caller   = sts:GetCallerIdentity(source)
 *
 *  caller is assumed-role/&lt;name&gt;-role-operator   → A  the source is used as is
 *  caller is assumed-role/&lt;other&gt;-role-operator  → B  refused: the credentials operate another deployment
 *  anything else                                 → C  assume &lt;name&gt;-role-operator in the caller's account
 * </pre>
 *
 * <p>The source is only ever the input to identifying the caller and to that assumption; no other
 * call is made with it. That is what makes falling back to the deployer's profile safe — deployer
 * rights never reach a day-to-day command — and it is why a failed assumption stops the command
 * instead of carrying on with the source.
 *
 * <p>The deployment is the one {@code --deployment} names, else the only one configured. On a machine
 * with none configured and no {@code --deployment} — a CI job federated into the operator role — it
 * is the one the caller's operator role belongs to: a role the stack created, whose name cannot move,
 * and which can only operate that deployment anyway.
 *
 * <p>The region is the file's; a machine with no file finds it from the deployment's bucket.
 */
public final class OperatorSession {

    /** The AWS calls this class makes, stood in by tests. */
    public interface Aws {
        /** {@code sts:GetCallerIdentity} under {@code source}. */
        Caller callerIdentity(AwsCredentialsProvider source, String region);

        /**
         * Credentials for {@code roleArn}, assumed from {@code source} and renewed for as long as the
         * process runs. Throws when the role cannot be assumed now.
         */
        AwsCredentialsProvider assume(AwsCredentialsProvider source, String region, String roleArn);

        /** The region {@code bucket} lives in, asked from {@code fromRegion}; empty when there is none. */
        Optional<String> bucketRegion(AwsCredentialsProvider credentials, String bucket, String fromRegion);
    }

    public record Caller(String arn, String account) {}

    private static final Pattern ASSUMED_ROLE = Pattern.compile("^arn:[^:]+:sts::\\d+:assumed-role/([^/]+)/.*$");

    private final ConfigService configs;
    private final Aws aws;
    private final Map<String, String> environment;

    private BaasConfig config;
    private Caller caller;
    private AwsCredentialsProvider source;

    public OperatorSession(ConfigService configs, Aws aws, Map<String, String> environment) {
        this.configs = configs;
        this.aws = aws;
        this.environment = environment;
    }

    /** The session real commands use. */
    public static OperatorSession of(ConfigService configs) {
        return new OperatorSession(configs, REAL, System.getenv());
    }

    /**
     * A session for a configuration not yet saved — {@code config sync} proving it can reach the
     * deployment before it writes anything.
     */
    public static OperatorSession adopting(BaasConfig config, Aws aws, Map<String, String> environment) {
        var session = new OperatorSession(null, aws, environment);
        session.config = config;
        return session;
    }

    /**
     * The deployment's configuration. Reads only local files, except on a machine with no deployment
     * configured and no {@code --deployment}, where the caller is identified to learn the name from
     * its operator role; anything else there fails as "no deployment is configured".
     */
    public BaasConfig config() {
        if (config != null) {
            return config;
        }
        if (configs.named().isPresent() || !configs.deployments().isEmpty()) {
            config = configs.load();
            return config;
        }
        BaasConfig fileless = new BaasConfig();
        config = fileless;
        String fromRole;
        try {
            fromRole = operatorRoleDeployment(caller()).orElse(null);
        } catch (SdkException noUsableCredentials) {
            // Nothing configured and nobody to ask: the same answer as no operator role.
            fromRole = null;
        }
        if (fromRole == null) {
            config = null;
            throw new DeploymentSelectionException(ConfigService.noneConfigured());
        }
        fileless.setPrefix(fromRole);
        return config;
    }

    /**
     * Clients for the deployment, running as its operator role. Nothing is resolved until the first
     * client is built, so a command that fails earlier, or a test that stands its AWS step in, makes
     * no call.
     */
    public AwsClientFactory factory() {
        BaasConfig resolved = config();
        return AwsClientFactory.deferred(() -> region(resolved), () -> operatorCredentials(resolved));
    }

    /** A&nbsp;/ B&nbsp;/ C, as the class comment draws them. */
    AwsCredentialsProvider operatorCredentials(BaasConfig resolved) {
        String name = resolved.requirePrefix();
        Caller identity = caller();
        Optional<String> roleOf = operatorRoleDeployment(identity);
        if (roleOf.isPresent() && roleOf.get().equals(name)) {
            return source();
        }
        if (roleOf.isPresent()) {
            throw new DeploymentSelectionException("""
                These credentials are the operator role of deployment '%s' (%s), not of '%s'. Nothing was done.
                  Use credentials for '%s', or name the deployment they operate: baas --deployment %s ..."""
                .formatted(roleOf.get(), identity.arn(), name, name, roleOf.get()));
        }
        String roleArn = "arn:aws:iam::" + identity.account() + ":role/" + DeploymentNames.of(name).operatorRole();
        try {
            return aws.assume(source(), startRegion(), roleArn);
        } catch (SdkException e) {
            throw new OperatorRoleException("""
                Cannot assume %s from %s (%s): %s
                  Nothing was done. That identity needs sts:AssumeRole on the role — ask the deployment's admin,
                  or choose another source: baas --deployment %s config set --operator-aws-profile <profile>"""
                .formatted(roleArn, sourceDescription(), identity.arn(), e.getMessage(), name), e);
        }
    }

    /** The file's region; with none, the region the deployment's bucket is in. */
    String region(BaasConfig resolved) {
        if (resolved.getAws().hasRegion()) {
            return resolved.getAws().getRegion();
        }
        return aws.bucketRegion(operatorCredentials(resolved), resolved.bucket(), startRegion())
            .orElseThrow(() -> new DeploymentSelectionException("""
                No deployment named '%s': there is no bucket of that name.
                  The name is the one `baas admin deployment setup` printed, e.g. baas-123456789012."""
                .formatted(resolved.requirePrefix())));
    }

    private Caller caller() {
        if (caller == null) {
            caller = aws.callerIdentity(source(), startRegion());
        }
        return caller;
    }

    private AwsCredentialsProvider source() {
        if (source == null) {
            source = AwsClientFactory.profile(currentConfig().getAws().operatorSourceProfile());
        }
        return source;
    }

    private String sourceDescription() {
        BaasConfig.AwsConfig settings = currentConfig().getAws();
        if (settings.getOperatorProfile() != null) return "profile " + settings.getOperatorProfile() + " (aws.operatorProfile)";
        if (settings.getDeployerProfile() != null) return "profile " + settings.getDeployerProfile() + " (aws.deployerProfile)";
        return "the default credential chain";
    }

    /** Where STS and the bucket lookup are asked from: the file's region, else AWS_REGION, else the default. */
    private String startRegion() {
        return currentConfig().getAws().resolveRegion(environment);
    }

    private BaasConfig currentConfig() {
        return config != null ? config : new BaasConfig();
    }

    static Optional<String> operatorRoleDeployment(Caller caller) {
        var matcher = ASSUMED_ROLE.matcher(caller.arn());
        return matcher.matches() ? DeploymentNames.ofOperatorRole(matcher.group(1)) : Optional.empty();
    }

    /** The operator role could not be assumed; the command stops rather than use the source. */
    public static final class OperatorRoleException extends RuntimeException {
        OperatorRoleException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final Aws REAL = new Aws() {
        @Override
        public Caller callerIdentity(AwsCredentialsProvider source, String region) {
            try (StsClient sts = StsClient.builder().region(Region.of(region)).credentialsProvider(source).build()) {
                var identity = sts.getCallerIdentity();
                return new Caller(identity.arn(), identity.account());
            }
        }

        @Override
        public AwsCredentialsProvider assume(AwsCredentialsProvider source, String region, String roleArn) {
            // Lives as long as the process: the provider renews through it. No duration is requested,
            // so STS grants its default hour — the most a chained session (an SSO source) may have,
            // where asking for more fails outright.
            StsClient sts = StsClient.builder().region(Region.of(region)).credentialsProvider(source).build();
            var provider = StsAssumeRoleCredentialsProvider.builder()
                .stsClient(sts)
                .asyncCredentialUpdateEnabled(true)
                .refreshRequest(r -> r.roleArn(roleArn).roleSessionName("baas-cli"))
                .build();
            provider.resolveCredentials();
            return provider;
        }

        @Override
        public Optional<String> bucketRegion(AwsCredentialsProvider credentials, String bucket, String fromRegion) {
            BucketProbe probe = BucketProbe.of(bucket, fromRegion,
                r -> S3Client.builder().region(Region.of(r)).credentialsProvider(credentials).build());
            return probe instanceof BucketProbe.Reachable(String found) ? Optional.of(found) : Optional.empty();
        }
    };
}
