package pl.wsztajerowski.baas.deployment.setup;

import pl.wsztajerowski.baas.session.DeploymentNames;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.baas.session.BaasConfig;
import pl.wsztajerowski.baas.session.DeploymentNameRule;
import pl.wsztajerowski.baas.aws.BucketProbe;

import java.util.Optional;

/**
 * Step 3: a new deployment's name and region; an existing one's are shown and fixed. Then the
 * check that the deployment does not already live in another region, which must come before the
 * permission check: the policy names the region.
 */
final class NameAndRegionStep implements SetupStep {

    static final String NAME = "name";
    private static final Logger logger = LoggerFactory.getLogger(NameAndRegionStep.class);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Outcome run(SetupContext context) {
        if (context.existing) {
            context.region = context.options.region() != null
                ? context.options.region()
                : context.config.getAws().resolveRegion();
            logger.info("Deployment {} in {}", context.deployment, context.region);
        } else {
            if (context.deployment == null) {
                context.deployment = newName(context);
            }
            if (context.region == null) {
                String region = context.options.region() != null
                    ? context.options.region()
                    : new BaasConfig().getAws().resolveRegion();
                if (context.options.region() == null && context.interactive()) {
                    region = context.prompter().ask("AWS region (cannot be changed later)", region);
                }
                context.region = region;
            }
            context.config.setPrefix(context.deployment);
            logger.info("New deployment {} in {}", context.deployment, context.region);
        }
        context.config.getAws().setRegion(context.region);

        // A deployer's grants name one region, so in another region the permission check would
        // print a policy for a second deployment that cannot exist. A bucket elsewhere means the
        // deployment is elsewhere: bucket names are global, and only a bucket the caller can read
        // is its own — another account's is the names step's to report.
        BucketProbe bucket = context.aws().bucket(context.deployment);
        if (bucket instanceof BucketProbe.Reachable(String bucketRegion) && !bucketRegion.equals(context.region)) {
            return new Outcome.Stop(1, bucketBlocksSetup(context.deployment, context.deployment, bucketRegion, context.region));
        }
        return SetupStep.next();
    }

    /** The suggested name is only that: any valid name is accepted, and unattended it is derived. */
    private static String newName(SetupContext context) {
        String suggested = computePrefix(context.caller.account());
        if (!context.interactive()) {
            logger.info("Using deployment: {} (derived from account {})", suggested, context.caller.account());
            return suggested;
        }
        while (true) {
            String answer = context.prompter().ask("Deployment name", suggested);
            Optional<String> violation = DeploymentNameRule.violation(answer);
            if (violation.isEmpty()) {
                return answer;
            }
            context.prompter().console().println("'" + answer + "' " + violation.get() + ".");
        }
    }

    static String bucketBlocksSetup(String bucket, String deployment, String bucketRegion, String region) {
        return """
            Deployment %1$s lives in %2$s, not %3$s: its bucket %4$s is there, and bucket
              names are global, so a deployment's name exists in one region only.
              Address it there:  baas --deployment %1$s admin deployment setup --region %2$s
            Nothing was deployed.""".formatted(deployment, bucketRegion, region, bucket);
    }

    /**
     * The default deployment's name stem: {@code baas-<accountId>}. Nothing about the calling
     * principal reaches it: an IAM user, an SSO session and a role-chained session on one account
     * all resolve to the same deployment — a name that moved with the caller forked the AMI and
     * the results table silently rather than failing.
     */
    static String computePrefix(String accountId) {
        return DeploymentNames.defaultFor(accountId);
    }
}
