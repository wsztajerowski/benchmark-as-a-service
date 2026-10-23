package pl.wsztajerowski.baas.deployment.setup;

import pl.wsztajerowski.baas.session.CliVersionCheck;
import pl.wsztajerowski.baas.session.CoreStack;
import pl.wsztajerowski.baas.session.DeploymentNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.baas.aws.BucketProbe;

import java.util.Optional;

/**
 * Step 5: the stack names its bucket and table after the deployment, so a taken name fails the
 * whole create. Checked after the permission step: a bucket that refuses this identity is then
 * another account's, not one this identity merely lacks the rights to read.
 */
final class NamesStep implements SetupStep {

    static final String NAME = "names";
    private static final Logger logger = LoggerFactory.getLogger(NamesStep.class);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Outcome run(SetupContext context) {
        context.stackState = context.aws().stackState(context.deployment);
        if (!context.creating()) {
            // An existing stack's bucket and table are its own. What it may refuse is this CLI.
            return olderThanRecorded(context).<Outcome>map(refusal -> new Outcome.Stop(1, refusal))
                .orElseGet(SetupStep::next);
        }
        BucketProbe bucket = context.aws().bucket(context.deployment);
        boolean tableExists = context.aws().tableExists(DeploymentNames.of(context.deployment).resultsTable());
        Optional<String> conflict = nameConflict(bucket, tableExists, context.deployment, context.region);
        if (conflict.isEmpty()) {
            return SetupStep.next();
        }
        if (bucket instanceof BucketProbe.Forbidden && context.interactive()) {
            // A flag answers its prompt in advance; it does not make an unusable answer usable.
            logger.warn("The bucket name {} belongs to another AWS account, and bucket names are global. "
                + "Choose another name.", context.deployment);
            context.deployment = null;
            return new Outcome.GoTo(NameAndRegionStep.NAME);
        }
        if (bucket instanceof BucketProbe.Forbidden) {
            return new Outcome.Stop(1, conflict.get());
        }
        // A leftover: named with the command that removes it, never removed by setup — it may hold
        // an earlier deployment's results. A rolled-back stack with one is left in place.
        return new Outcome.Wait(conflict.get() + (context.interactive()
            ? "\nOnce it is gone, press Enter to check again."
            : ""));
    }

    /**
     * Why this CLI may not update the stack: it is older than the release that last set it up, and
     * its template would revert the newer one's. Read once a run — a permission hand-over resumes
     * before this step. Not a wait: the process that would wait is the CLI that has to be replaced.
     */
    private static Optional<String> olderThanRecorded(SetupContext context) {
        if (context.recordedCliVersion != null) {
            return Optional.empty();
        }
        context.recordedCliVersion = context.aws().stackParameters(context.deployment)
            .getOrDefault(CoreStack.PARAM_BAAS_CLI_VERSION, "");
        try {
            context.versionCheck.check(context.deployment, CliVersionCheck.Role.SETUP,
                () -> Optional.of(context.recordedCliVersion));
            return Optional.empty();
        } catch (CliVersionCheck.Refused refused) {
            return Optional.of(refused.getMessage());
        }
    }

    /**
     * Why a create cannot go ahead because a name the stack would create is taken, or empty when
     * both are free.
     */
    static Optional<String> nameConflict(BucketProbe bucket, boolean tableExists, String deployment, String region) {
        String table = DeploymentNames.of(deployment).resultsTable();
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
}
