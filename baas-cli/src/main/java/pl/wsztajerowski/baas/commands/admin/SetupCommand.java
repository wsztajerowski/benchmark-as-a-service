package pl.wsztajerowski.baas.commands.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    // ─── GitHub OIDC federation ──────────────────────────────────────────────────
    //
    // These three set the federated trust on BaasCliOperatorRole. Omitting them on a later setup
    // leaves the deployed trust untouched rather than dropping it: the update path carries every
    // unnamed parameter forward with UsePreviousValue. That is why revocation needs its own
    // gesture — nothing should be able to cut CI's access to an installation by accident.
    //
    // No BaasConfig field backs any of them. The deployed stack's parameters are the single
    // source of truth for what the trust policy says; a second copy in ~/.baas/config.yaml would
    // drift, and would make "whose laptop ran setup last" part of whether CI keeps working.

    @Option(names = "--github-org",
        description = "GitHub organisation or user whose workflows may assume the operator role.")
    String githubOrg;

    @Option(names = "--github-repo", split = ",",
        description = "Repository name allowed to assume the operator role. Repeatable, or "
            + "comma-separated; one installation can serve several repositories.")
    List<String> githubRepos = new ArrayList<>();

    @Option(names = "--oidc-provider-arn",
        description = "ARN of the account's existing GitHub OIDC identity provider. Forwarded to "
            + "CloudFormation verbatim — no IAM call looks it up, so the CI stack stays outside "
            + "every baas command's reach.")
    String oidcProviderArn;

    @Option(names = "--revoke-github-oidc",
        description = "Remove the federated trust statement, submitting the federation parameters "
            + "empty. The only way an update removes it — omitting the setting options carries "
            + "the deployed values forward instead.")
    boolean revokeGithubOidc;

    @Spec CommandSpec spec;

    private final ConfigService configService = new ConfigService();

    @Override
    public Integer call() throws Exception {
        // Before anything is loaded, resolved or deployed.
        validateFederationOptions();

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
        String resolvedPrefix = computePrefix(accountId);
        String resolvedStack = resolvedPrefix;

        config.setPrefix(resolvedPrefix);

        logger.info("Using installation: {} (derived from account {})", resolvedPrefix, accountId);

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
        } catch (IllegalStateException e) {
            // A refused precondition — the networking-immutability check most of all. It is an
            // expected outcome, so it exits 1 with its own message rather than surfacing through
            // the generic handler, which invites the reader to go looking for a stack trace.
            // Must precede the RuntimeException catch below: IllegalStateException is one.
            logger.error(e.getMessage());
            return 1;
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
        // Networking is sent only when this invocation names it. Sending it unconditionally is
        // what let a plain `baas admin setup` rebuild a shared installation's networking; see
        // networkingParameters().
        params.putAll(networkingParameters());
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
            // Derived once, by BaasConfig, like every other consumer. Composing "baas-" here a
            // second time is how this asked for `baas-baas-<account>-results` — the namespace
            // lives inside the prefix value now.
            String bucketName = config.bucket();
            String tableName = config.resultsTable();
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
            var cloudFormation = new CloudFormationService(cf);
            if (cloudFormation.stackExists(resolvedStack)) {
                // Networking is fixed at creation. Checked before anything is submitted, so a
                // refused update leaves the stack untouched rather than rolling back.
                requireNetworkingUnchanged(
                    networkingParameters(), cloudFormation.getStackParameters(resolvedStack));

                // Only the parameters this command owns are sent; every other one — the three
                // federation parameters above all — is carried forward with UsePreviousValue, so
                // a setup run for an unrelated reason cannot silently revoke CI's access. Same
                // mechanism, and the same failure, as the `UseExistingVpc` case its Javadoc names.
                params.putAll(federationParameters());
                cloudFormation.updateStackParameters(resolvedStack, templateBody, params);
            } else {
                // UsePreviousValue is rejected on stack creation and on any parameter with no
                // previous value, so a first deploy sends explicit values — the ones this
                // invocation named, or empty when it named none. Carry-forward governs updates
                // only.
                params.putAll(federationParametersForCreate());
                cloudFormation.createOrUpdateStack(resolvedStack, templateBody, params);
            }
        }

        // Only the operator role ARN is read back, and only to print it. The bucket, results
        // table and instance profile are derived from the prefix, and the subnet and security
        // group are resolved from this stack each time they are needed — storing either kind is
        // how a config file comes to name one installation while `prefix` names another.
        String operatorRoleArn;
        try (var cf = factory.cloudFormation()) {
            operatorRoleArn = new CloudFormationService(cf)
                .getStackOutputs(resolvedStack).getOrDefault("OperatorRoleArn", "");
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
    /**
     * The federation parameters this invocation names, or empty when it names none. An empty map
     * on the update path means "carry the deployed values forward".
     *
     * <p>Revocation submits all three explicitly empty, which turns the template's
     * {@code FederateGitHub} condition false and takes the federated statement with it. The
     * account-root principal is a separate statement and survives either way, so neither
     * federating nor revoking can lock a local operator out.
     */
    Map<String, String> federationParameters() {
        if (revokeGithubOidc) {
            return noFederation();
        }
        if (githubOrg == null && githubRepos.isEmpty() && oidcProviderArn == null) {
            return Map.of();
        }
        return Map.of(
            "GitHubOidcProviderArn", oidcProviderArn,
            "GitHubOrg", githubOrg,
            "GitHubRepo", subjectPatterns(githubOrg, githubRepos));
    }

    /**
     * What a create submits: the federation values this invocation named, or all three empty when
     * it named none.
     *
     * <p>A create cannot use {@code UsePreviousValue} — CloudFormation rejects it for a parameter
     * with no previous value — so it has to send explicit values for all three. This used to send
     * them unconditionally <em>empty</em>, which discarded the options the caller had just typed:
     * {@code baas admin setup --github-org … --oidc-provider-arn …} against a fresh stack reported
     * success and deployed an operator role with no federated principal, so CI could not assume
     * it. The bug was invisible for as long as every federated installation happened to have been
     * federated by an update rather than a create.
     */
    Map<String, String> federationParametersForCreate() {
        Map<String, String> named = federationParameters();
        return named.isEmpty() ? noFederation() : named;
    }

    /**
     * All three submitted explicitly empty, which turns the template's {@code FederateGitHub}
     * condition false. Held here rather than in either caller so the two cannot recurse into each
     * other — they did, briefly, and the tests caught it as a StackOverflowError.
     */
    private static Map<String, String> noFederation() {
        return Map.of("GitHubOidcProviderArn", "", "GitHubOrg", "", "GitHubRepo", "");
    }

    /**
     * One OIDC subject pattern per repository, composed here rather than in the template:
     * CloudFormation cannot iterate a list, and every in-template trick for it relies on
     * {@code Fn::Sub} re-scanning text substituted into it, which it does not do. The template
     * refs the resulting {@code CommaDelimitedList} straight into the {@code StringLike}
     * condition, so any number of repositories works with no template change.
     *
     * <p>The trailing {@code :*} admits every workflow and ref in the repository. Fork pull
     * requests cannot obtain an {@code id-token} at all, so it is not reachable from a fork.
     */
    static String subjectPatterns(String org, List<String> repos) {
        return repos.stream()
            .map(String::strip)
            .filter(repo -> !repo.isEmpty())
            .map(repo -> "repo:" + org + "/" + repo + ":*")
            .collect(java.util.stream.Collectors.joining(","));
    }

    /**
     * Naming some federation options but not all would deploy a condition that silently evaluates
     * false — a setup that reports success and leaves CI with no access. Rejected outright, the
     * same stance {@code baas run} takes on a {@code --tag} for a reserved key.
     */
    void validateFederationOptions() {
        boolean anySetting = githubOrg != null || !githubRepos.isEmpty() || oidcProviderArn != null;
        if (revokeGithubOidc && anySetting) {
            throw new ParameterException(spec.commandLine(),
                "--revoke-github-oidc cannot be combined with --github-org, --github-repo or "
                    + "--oidc-provider-arn. Revoking and setting at once has no sensible "
                    + "precedence. Nothing was deployed.");
        }
        if (!anySetting) {
            return;
        }
        List<String> missing = new ArrayList<>();
        if (oidcProviderArn == null) missing.add("--oidc-provider-arn");
        if (githubOrg == null) missing.add("--github-org");
        if (githubRepos.isEmpty()) missing.add("--github-repo");
        if (!missing.isEmpty()) {
            throw new ParameterException(spec.commandLine(),
                "GitHub federation needs all of --oidc-provider-arn, --github-org and "
                    + "--github-repo; missing " + String.join(", ", missing)
                    + ". A partial set deploys a trust condition that is always false, so the "
                    + "stack would report success while continuous integration has no access. "
                    + "Nothing was deployed.");
        }
    }

    /**
     * The installation's name stem: {@code baas-<accountId>}, plus the mode's suffix.
     *
     * <p>The whole name, including the {@code baas-} namespace, lives in this one value, so every
     * resource is {@code <prefix>} or {@code <prefix>-<suffix>} and a reader who knows the prefix
     * can predict every name. Nothing about the calling principal reaches it: an IAM user, an SSO
     * session and a role-chained session on one account all resolve to the same installation. That
     * is the point — the AMI and the results table are account-level assets, and a name that moved
     * with the caller forked them silently rather than failing.
     */
    /** The four networking parameters, or empty when this invocation names none of them. */
    Map<String, String> networkingParameters() {
        if (!useExistingVpc && existingVpcId == null && existingSubnetId == null
            && existingSecurityGroupId == null) {
            return Map.of();
        }
        Map<String, String> networking = new LinkedHashMap<>();
        networking.put("UseExistingVpc", Boolean.toString(useExistingVpc));
        networking.put("ExistingVpcId", existingVpcId != null ? existingVpcId : "");
        networking.put("ExistingSubnetId", existingSubnetId != null ? existingSubnetId : "");
        networking.put("ExistingSecurityGroupId",
            existingSecurityGroupId != null ? existingSecurityGroupId : "");
        return networking;
    }

    /**
     * Refuses an update that would move the installation onto different networking.
     *
     * <p>Carrying the submitted values forward instead would close the same hole, but silently:
     * the operator typed a flag and it would be discarded without a word. Refusing names the
     * deployed value and the submitted one and submits nothing, so the operator can decide.
     *
     * <p>Replacing a subnet or security group under a running installation moves resource ids that
     * other machines' configuration and in-flight runs are holding, which is why this is immutable
     * rather than merely discouraged.
     */
    static void requireNetworkingUnchanged(Map<String, String> submitted,
                                           Map<String, String> deployed) {
        List<String> conflicts = submitted.entrySet().stream()
            .filter(entry -> deployed.containsKey(entry.getKey()))
            .filter(entry -> !entry.getValue().equals(deployed.get(entry.getKey())))
            .map(entry -> "  %s: deployed %s, submitted %s".formatted(
                entry.getKey(),
                deployed.get(entry.getKey()).isEmpty() ? "(none)" : deployed.get(entry.getKey()),
                entry.getValue().isEmpty() ? "(none)" : entry.getValue()))
            .toList();
        if (!conflicts.isEmpty()) {
            throw new IllegalStateException("""
                This installation is already deployed against different networking.
                %s
                Networking is fixed when an installation is created. Omit the networking options to
                keep what is deployed, or tear down and recreate the installation to change it.
                Nothing was submitted."""
                .formatted(String.join("\n", conflicts)));
        }
    }

    static String computePrefix(String accountId) {
        if (accountId == null || !accountId.matches("\\d{12}")) {
            throw new IllegalArgumentException(
                "Expected a 12-digit AWS account id, got: " + accountId);
        }
        return "baas-" + accountId;
    }

    private String loadTemplate() throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/templates/cf-template-core.yaml")) {
            if (is == null) throw new IllegalStateException("CF template not found in classpath");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
