package pl.wsztajerowski.baas.deployment.setup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.baas.session.DeploymentNames;

import java.time.Duration;
import java.util.List;

/**
 * Step 9: day-to-day commands run as the operator role, assumed from the deployer's credentials,
 * so that assumption is proven here. A role created seconds ago may not be assumable yet, so a
 * refusal is retried for about 30 seconds before the policy is suspected.
 */
final class OperatorRoleStep implements SetupStep {

    static final String NAME = "operator-role";
    private static final Logger logger = LoggerFactory.getLogger(OperatorRoleStep.class);
    static final List<Duration> BACKOFF = List.of(
        Duration.ZERO, Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8), Duration.ofSeconds(16));

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Outcome run(SetupContext context) throws InterruptedException {
        String roleArn = context.operatorRoleArn != null && !context.operatorRoleArn.isEmpty()
            ? context.operatorRoleArn
            : "arn:aws:iam::" + context.caller.account() + ":role/" + DeploymentNames.of(context.deployment).operatorRole();
        RuntimeException last = null;
        for (Duration delay : BACKOFF) {
            if (!delay.isZero()) {
                context.sleeper.sleep(delay);
            }
            try {
                context.aws().assumeRole(roleArn);
                logger.info("Operator role: assumable from these credentials ({})", roleArn);
                return SetupStep.next();
            } catch (RuntimeException e) {
                last = e;
                logger.debug("Assuming {} failed: {}", roleArn, e.getMessage());
            }
        }
        logger.error("These credentials cannot assume {} even after about 30 seconds: {}. "
            + "The deployer policy may be out of date.", roleArn, last.getMessage());
        return StackStep.permissionFailure(context);
    }
}
