package pl.wsztajerowski.baas.commands.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;
import pl.wsztajerowski.baas.BaasApp;
import pl.wsztajerowski.baas.LoggingMixin;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.config.ConfigService;
import pl.wsztajerowski.baas.config.DeploymentNameRule;
import pl.wsztajerowski.baas.infra.AwsClientFactory;
import pl.wsztajerowski.baas.infra.CloudFormationService;
import pl.wsztajerowski.baas.infra.DeployerPolicyRenderer;
import pl.wsztajerowski.baas.infra.DeployerPreflight;
import pl.wsztajerowski.baas.infra.ParentImageResolver;
import pl.wsztajerowski.baas.infra.RunnerImageExtension;
import pl.wsztajerowski.baas.infra.RunnerImageParameters;
import pl.wsztajerowski.baas.infra.RunnerImageRenderer;
import pl.wsztajerowski.baas.infra.BucketProbe;
import software.amazon.awssdk.services.cloudformation.model.StackStatus;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    @Option(names = "--deployer-aws-profile",
        description = "AWS CLI profile with the deployer policy; stored for every `baas admin` command.")
    String deployerProfile;

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
    // gesture — nothing should be able to cut CI's access to a deployment by accident.
    //
    // No BaasConfig field backs any of them. The deployed stack's parameters are the single
    // source of truth for what the trust policy says; a second copy in ~/.baas/config.yaml would
    // drift, and would make "whose laptop ran setup last" part of whether CI keeps working.

    @Option(names = "--github-org",
        description = "GitHub organisation or user whose workflows may assume the operator role.")
    String githubOrg;

    @Option(names = "--github-repo", split = ",",
        description = "Repository name allowed to assume the operator role. Repeatable, or "
            + "comma-separated; one deployment can serve several repositories.")
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

    private ConfigService configService() {
        return BaasApp.configService(spec);
    }

    @Override
    public Integer call() throws Exception {
        // Before anything is loaded, resolved or deployed.
        validateFederationOptions();
        validateNetworkingOptions();
        // Before any AWS call, the policy step included: a name some composed resource cannot carry
        // would otherwise fail minutes into a deploy, or print a policy for a deployment that can
        // never exist. A derived name needs no check; baas-<12 digits> always passes.
        var named = BaasApp.deployment(spec);
        if (named.isPresent()) {
            Optional<String> violation = DeploymentNameRule.violation(named.get());
            if (violation.isPresent()) {
                logger.error("Deployment name '{}' {}. Nothing was done.", named.get(), violation.get());
                return 2;
            }
        }

        // The named deployment, existing or new; else the only one configured; else, with none, an
        // empty configuration whose name is derived from the account below.
        BaasConfig config;
        try {
            config = configService().loadForSetup();
        } catch (IllegalStateException e) {
            logger.error(e.getMessage());
            return 2;
        }
        if (region != null) config.getAws().setRegion(region);
        if (deployerProfile != null) config.getAws().setDeployerProfile(deployerProfile);

        // Recorded in the file: the deployment lives in one region, and the machine that
        // created it should keep addressing that region whatever the environment later says.
        String resolvedRegion = config.getAws().resolveRegion();
        config.getAws().setRegion(resolvedRegion);

        var factory = new AwsClientFactory(resolvedRegion, config.getAws().getDeployerProfile());

        String callerArn;
        String accountId;
        try (var sts = factory.sts()) {
            var identity = sts.getCallerIdentity();
            callerArn = identity.arn();
            accountId = identity.account();
        }
        logger.debug("Caller ARN: {}", callerArn);
        boolean derived = config.getPrefix() == null || config.getPrefix().isBlank();
        String resolvedPrefix = deploymentName(config, accountId);
        config.setPrefix(resolvedPrefix);

        if (derived) {
            logger.info("Using deployment: {} (derived from account {})", resolvedPrefix, accountId);
        } else {
            logger.info("Using deployment: {} (account {})", resolvedPrefix, accountId);
        }

        // Before the preflight: a deployer's grants name one region, so in another region the
        // preflight would print a policy for it and invite granting a second deployment that
        // cannot exist. The stack creates the bucket in its own region, so a bucket elsewhere means
        // the deployment is elsewhere — no stack lookup is needed, and HeadBucket is global. Only a
        // bucket the caller can read is its deployment's: another account's is left to the create
        // path, which refuses it there.
        BucketProbe bucket = BucketProbe.of(config.bucket(), resolvedRegion, factory::s3In);
        if (bucket instanceof BucketProbe.Reachable(String bucketRegion) && !bucketRegion.equals(resolvedRegion)) {
            logger.error(bucketBlocksSetup(config.bucket(), resolvedPrefix, bucketRegion, resolvedRegion));
            return 1;
        }

        // The deployer policy is a step of setup, not a command of its own: rendered for this
        // deployment and checked against the caller's rights before anything is created. When
        // rights fall short, the policy is the payload (stdout, so `setup > policy.json` hands it
        // over) and what is missing is the diagnostic (stderr).
        String policy = new DeployerPolicyRenderer().render(accountId, resolvedRegion, resolvedPrefix);
        List<String> denied;
        try (var iam = factory.iam()) {
            denied = new DeployerPreflight(iam).simulateCriticalActions(callerArn, accountId, resolvedRegion, resolvedPrefix);
        }
        Integer refused = refusalForMissingRights(denied, policy, accountId, resolvedRegion, resolvedPrefix);
        if (refused != null) {
            return refused;
        }

        try {
            return deploy(factory, config, resolvedPrefix, bucket);
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
            // answer, and it is deployment-specific — there is no generic version to link to.
            logger.error("{}\n\nThis identity is missing a permission `baas admin deployment setup` needs. "
                + "Attach the policy printed on standard output (rendered for account {}, region {}, "
                + "deployment {}), then re-run.", e.getMessage(), accountId, resolvedRegion, resolvedPrefix);
            printPolicy(policy);
            return 1;
        }
    }

    /**
     * Exit code 1, the missing actions on stderr and the policy on stdout, when the simulator
     * denied anything; {@code null} to carry on — also when the caller may not simulate at all,
     * since the preflight is a convenience, not a control.
     */
    Integer refusalForMissingRights(List<String> denied, String policy, String accountId, String region,
                                    String deployment) {
        if (denied.isEmpty()) {
            return null;
        }
        logger.error("This identity cannot {}. Attach the policy printed on standard output (rendered for "
            + "account {}, region {}, deployment {}), then re-run. Nothing was created.",
            String.join(", ", denied), accountId, region, deployment);
        printPolicy(policy);
        return 1;
    }

    /** IAM JSON is payload: never styled, never prefixed, so it can be redirected into a file. */
    private void printPolicy(String policy) {
        pl.wsztajerowski.baas.console.Console.of(spec.commandLine().getOut()).println(policy);
    }

    /** The stack is named by the prefix: the stack is the deployment. */
    private Integer deploy(AwsClientFactory factory, BaasConfig config, String resolvedPrefix,
                           BucketProbe bucket) throws Exception {
        String templateBody = CloudFormationService.coreTemplate();

        Map<String, String> params = new LinkedHashMap<>();
        params.put("ResourceNamePrefix", resolvedPrefix);
        // Networking is sent only when this invocation names it. Sending it unconditionally is
        // what let a plain `baas admin deployment setup` rebuild a shared deployment's networking; see
        // networkingParameters().
        params.putAll(networkingParameters());

        boolean created;
        try (var cf = factory.cloudFormation()) {
            var cloudFormation = new CloudFormationService(cf);
            Optional<StackStatus> status = cloudFormation.stackStatus(resolvedPrefix);
            // A first create that rolled back holds no resources and cannot be updated, and no
            // configuration file was written for it — re-running setup is the whole recovery.
            boolean rolledBack = status.isPresent() && status.get() == StackStatus.ROLLBACK_COMPLETE;
            created = status.isEmpty() || rolledBack;
            if (created) {
                // Before anything changes, the rolled-back stack included: the stack names its
                // bucket and table after the deployment, so a taken name fails the whole create —
                // and, recovered from automatically, would fail it again on every re-run.
                boolean tableExists;
                try (var dynamoDb = factory.dynamoDb()) {
                    tableExists = tableExists(dynamoDb, resolvedPrefix + "-results");
                }
                Optional<String> conflict = nameConflict(bucket, tableExists, resolvedPrefix,
                    config.getAws().resolveRegion());
                if (conflict.isPresent()) {
                    throw new IllegalStateException(conflict.get());
                }
            }
            if (rolledBack) {
                logger.info("Stack {} is in ROLLBACK_COMPLETE: an earlier create failed and left nothing behind. "
                    + "Deleting it before creating it again.", resolvedPrefix);
                cloudFormation.deleteStack(resolvedPrefix);
            }
            if (!created) {
                // Networking is fixed at creation. Checked before anything is submitted, so a
                // refused update leaves the stack untouched rather than rolling back.
                requireNetworkingUnchanged(
                    networkingParameters(), cloudFormation.getStackParameters(resolvedPrefix));

                // Only the parameters this command owns are sent; every other one — the three
                // federation parameters above all — is carried forward with UsePreviousValue, so
                // a setup run for an unrelated reason cannot silently revoke CI's access. Same
                // mechanism, and the same failure, as the `UseExistingVpc` case its Javadoc names.
                params.putAll(federationParameters());
                // The image is `baas admin image build`'s to change: everything it deployed —
                // base, parent, extension — is carried forward, so a plain setup never reverts an
                // image or drops an extension. Only parameters the stack lacks are sent, which is
                // how a deployment from before a parameter existed gets a real value for it.
                Map<String, String> deployed = cloudFormation.getStackParameters(resolvedPrefix);
                params.putAll(imageParameters(factory, config.getAws().resolveRegion(), deployed).absentFrom(deployed));
                cloudFormation.updateStackParameters(resolvedPrefix, templateBody, params);
            } else {
                // UsePreviousValue is rejected on stack creation and on any parameter with no
                // previous value, so a first deploy sends explicit values — the ones this
                // invocation named, or empty when it named none. Carry-forward governs updates
                // only.
                params.putAll(federationParametersForCreate());
                // The same plan `baas admin image build` submits. Letting the template's
                // placeholder defaults stand would register a no-op component at a version, and
                // Image Builder would then refuse the real one at that same version —
                // immutability, hit from a direction nobody would think to look.
                params.putAll(imageParameters(factory, config.getAws().resolveRegion(), Map.of()).values());
                cloudFormation.createStack(resolvedPrefix, templateBody, params);
            }
        }

        // Only the operator role ARN is read back, and only to print it. The bucket, results
        // table and instance profile are derived from the prefix, and the subnet and security
        // group are resolved from this stack each time they are needed — storing either kind is
        // how a config file comes to name one deployment while `prefix` names another.
        String operatorRoleArn;
        try (var cf = factory.cloudFormation()) {
            operatorRoleArn = new CloudFormationService(cf)
                .getStackOutputs(resolvedPrefix).getOrDefault("OperatorRoleArn", "");
        }

        configService().save(config);
        logger.info("Configuration written to {}", configService().fileOf(resolvedPrefix));
        // Beside deployments/, never in it: everything in deployments/ is read as a deployment.
        writeExtensionStarter(configService().root().resolve(EXTENSION_STARTER_FILE));

        logger.info("{}", nextSteps(created, operatorRoleArn, resolvedPrefix));

        return 0;
    }

    /**
     * Why an existing bucket blocks a create. Bucket names are global, so the bucket answers from
     * any region. In another region it is the account's deployment — this setup was simply aimed
     * at the wrong region — and the advice once given here, {@code aws s3 rb --force}, would have
     * deleted that deployment's results. Only a bucket in this region is a retained leftover
     * Called only for a bucket in another region.
     */
    /**
     * Why a create cannot go ahead because a name the stack would create is taken, or empty when
     * both are free. A leftover is named, never removed: it may hold an earlier deployment's results.
     */
    static Optional<String> nameConflict(BucketProbe bucket, boolean tableExists, String deployment, String region) {
        String table = deployment + "-results";
        if (bucket instanceof BucketProbe.Forbidden(String bucketRegion)) {
            return Optional.of("""
                The bucket name %1$s is taken by another AWS account (it answers from %2$s and refuses \
                this identity), and bucket names are global, so deployment %1$s cannot be created.
                  Choose another name:  baas --deployment <name> admin deployment setup
                  (If the bucket is this account's, the identity lacks the deployer policy's s3:ListBucket on it.)
                Nothing was deployed.""".formatted(deployment, bucketRegion));
        }
        if (bucket instanceof BucketProbe.Reachable(String bucketRegion) && bucketRegion.equals(region)) {
            return Optional.of("""
                Bucket %1$s already exists in %2$s with no deployment stack, so the stack cannot create it — \
                a leftover of an earlier deployment or one made by hand. It may hold earlier results.
                  Save what you need, then remove it:  aws s3 rb s3://%1$s --force
                Nothing was deployed.""".formatted(deployment, bucketRegion));
        }
        if (tableExists) {
            return Optional.of("""
                Table %1$s already exists in %2$s with no deployment stack, so the stack cannot create it — \
                a leftover of an earlier deployment. It may hold earlier results.
                  Export what you need, then remove it:  aws dynamodb delete-table --table-name %1$s --region %2$s
                Nothing was deployed.""".formatted(table, region));
        }
        return Optional.empty();
    }

    private static boolean tableExists(DynamoDbClient dynamoDb, String table) {
        try {
            dynamoDb.describeTable(r -> r.tableName(table));
            return true;
        } catch (ResourceNotFoundException e) {
            return false;
        }
    }

    static String bucketBlocksSetup(String bucket, String deployment, String bucketRegion, String region) {
        return """
            Deployment %1$s lives in %2$s, not %3$s: its bucket %4$s is there, and bucket
              names are global, so a deployment's name exists in one region only.
              Address it there:  baas admin deployment setup --region %2$s
            Nothing was deployed.""".formatted(deployment, bucketRegion, region, bucket);
    }

    static final String EXTENSION_STARTER_FILE = "runner-image-extension.yaml";

    /**
     * The image parameters for this submission. Deployed values win wherever they exist, so the
     * plan describes the image the deployment already has; the bundled base and a freshly
     * resolved parent fill in only what is missing, which on a create is everything.
     */
    private RunnerImageParameters imageParameters(AwsClientFactory factory, String region, Map<String, String> deployed) {
        var renderer = new RunnerImageRenderer();
        String parentAmiId = deployed.get(RunnerImageParameters.PARENT_AMI_ID);
        if (parentAmiId == null) {
            try (var ec2 = factory.ec2()) {
                parentAmiId = new ParentImageResolver(ec2)
                    .resolve(renderer.definition().parentImage().amiName(), region);
            }
        }
        return RunnerImageParameters.plan(renderer,
            deployed.getOrDefault(RunnerImageParameters.IMAGE_VERSION, renderer.definition().imageVersion()),
            deployed.getOrDefault(RunnerImageParameters.COMPONENT_DATA, renderer.renderBase()),
            parentAmiId,
            deployed.getOrDefault(RunnerImageParameters.EXTENSION_DATA, ""),
            deployed);
    }

    /**
     * The operator's starting point for extending the runner image — the same document a pull of an
     * deployment without an extension prints. Never overwritten: it may hold edits, and the
     * stale-push guard makes an old copy harmless rather than something to refresh.
     */
    static void writeExtensionStarter(Path file) {
        if (Files.exists(file)) {
            return;
        }
        try {
            Files.writeString(file, RunnerImageExtension.withMarker(""));
            logger.info("""
                Runner-image extension starter written to {}
                  Edit it, then: baas admin image build --extension {}""", file, file);
        } catch (IOException e) {
            // A convenience, not part of the deployment: the pull prints the same document.
            logger.warn("Could not write {}: {}. `baas admin image show --extension` prints the same starter.",
                file, e.getMessage());
        }
    }

    /**
     * What to do next. The onboarding steps are true only of a stack this job created: on an update
     * the operator role already existed — someone may well be assuming it — and the image may be
     * built, so repeating "nobody can assume it yet" and "build the image" there was simply wrong.
     */
    static String nextSteps(boolean created, String operatorRoleArn, String prefix) {
        if (!created) {
            return "Deployment " + prefix + " is deployed."
                + (operatorRoleArn.isEmpty() ? "" : " Operator role: " + operatorRoleArn);
        }
        // Setup deliberately does not build the image — that is a ~15-minute operation and every
        // re-setup would pay for it. It is a hard precondition of `baas run`, so say so here
        // rather than letting the first job be where the user finds out.
        return """
            BaasCliOperatorRole created: %s
            Nobody can assume it yet. Two one-time steps:
              1. Grant sts:AssumeRole on this ARN to the IAM user who runs benchmarks,
                 and add a ~/.aws/config profile with role_arn + source_profile. See infra/README.md.
              2. Point the CLI at that profile:
                   baas config set --operator-aws-profile <profile-name>
                 Until you do, `baas run` uses the default credential chain, not this role.
            Next: build the runner image.
                  baas admin image build
                Takes ~15 minutes and publishes an AMI to /%s/runner/ami-id.
                `baas run` fails until it exists — there is no boot-time install path."""
            .formatted(operatorRoleArn.isEmpty() ? "(no OperatorRoleArn output)" : operatorRoleArn, prefix);
    }

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
     * {@code baas admin deployment setup --github-org … --oidc-provider-arn …} against a fresh stack reported
     * success and deployed an operator role with no federated principal, so CI could not assume
     * it. The bug was invisible for as long as every federated deployment happened to have been
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
    /**
     * The networking ids mean something only with {@code --use-existing-vpc}. Without it a create
     * built new networking and ignored them without a word, and an update refused them as
     * "different networking" — so both halves of the pair are required together, the same stance
     * {@link #validateFederationOptions()} takes on a partial federation set.
     */
    void validateNetworkingOptions() {
        boolean anyId = existingVpcId != null || existingSubnetId != null || existingSecurityGroupId != null;
        if (useExistingVpc && (existingVpcId == null || existingSubnetId == null || existingSecurityGroupId == null)) {
            throw new ParameterException(spec.commandLine(),
                "--use-existing-vpc requires --vpc-id, --subnet-id, and --sg-id. Nothing was deployed.");
        }
        if (!useExistingVpc && anyId) {
            throw new ParameterException(spec.commandLine(),
                "--vpc-id, --subnet-id and --sg-id apply only with --use-existing-vpc; without it setup "
                    + "builds its own networking and would ignore them. Nothing was deployed.");
        }
    }

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
     * Refuses an update that would move the deployment onto different networking.
     *
     * <p>Carrying the submitted values forward instead would close the same hole, but silently:
     * the operator typed a flag and it would be discarded without a word. Refusing names the
     * deployed value and the submitted one and submits nothing, so the operator can decide.
     *
     * <p>Replacing a subnet or security group under a running deployment moves resource ids that
     * other machines' configuration and in-flight jobs are holding, which is why this is immutable
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
                This deployment is already deployed against different networking.
                %s
                Networking is fixed when a deployment is created. Omit the networking options to
                keep what is deployed, or tear down and recreate the deployment to change it.
                Nothing was submitted."""
                .formatted(String.join("\n", conflicts)));
        }
    }

    /**
     * The deployment setup acts on: the one the configuration already names — from
     * {@code --deployment}, or the only one configured — else the account's default.
     */
    static String deploymentName(BaasConfig config, String accountId) {
        String configured = config.getPrefix();
        return configured == null || configured.isBlank() ? computePrefix(accountId) : configured;
    }

    /**
     * The default deployment's name stem: {@code baas-<accountId>}, used when {@code --deployment} is
     * absent and no deployment is configured. A named deployment's prefix is its name, verbatim.
     *
     * <p>The whole name, including the {@code baas-} namespace, lives in this one value, so every
     * resource is {@code <prefix>} or {@code <prefix>-<suffix>} and a reader who knows the prefix
     * can predict every name. Nothing about the calling principal reaches it: an IAM user, an SSO
     * session and a role-chained session on one account all resolve to the same deployment. That
     * is the point — the AMI and the results table are account-level assets, and a name that moved
     * with the caller forked them silently rather than failing.
     */
    static String computePrefix(String accountId) {
        if (accountId == null || !accountId.matches("\\d{12}")) {
            throw new IllegalArgumentException(
                "Expected a 12-digit AWS account id, got: " + accountId);
        }
        return "baas-" + accountId;
    }
}
