package pl.wsztajerowski.baas.commands.admin.setup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.baas.infra.DeployerPolicyRenderer;

/**
 * Step 4: the deployer policy, rendered for this deployment and checked against the caller's
 * rights before anything is created. A convenience, not a control: a check that cannot run lets
 * the run continue, and a missing right is then reported when the call is refused.
 */
final class PermissionsStep implements SetupStep {

    static final String NAME = "permissions";
    private static final Logger logger = LoggerFactory.getLogger(PermissionsStep.class);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Outcome run(SetupContext context) {
        context.policy = new DeployerPolicyRenderer().render(context.caller.account(), context.region, context.deployment);
        // Before the result is acted on, whatever it is: an administrator rendering a teammate's
        // policy, and an upgrade whose failure only CloudFormation reports later, both need the file.
        if (context.options.policyFile() != null && !PolicyHandOver.write(context.options.policyFile(), context.policy)) {
            return new Outcome.Stop(1, "Nothing was created.");
        }
        context.preflight = context.aws().preflight(context.caller.arn(), context.policy);
        return switch (context.preflight) {
            case SetupAws.Preflight.Met met -> {
                logger.info("Deployer permissions: met");
                yield SetupStep.next();
            }
            case SetupAws.Preflight.NotChecked notChecked -> {
                logger.info("Deployer permissions: not checked (this identity may not simulate IAM policies). "
                    + "A missing permission will be reported when it is used.");
                yield SetupStep.next();
            }
            case SetupAws.Preflight.Denied denied -> {
                logger.error("This identity cannot {}. Nothing was created.", String.join(", ", denied.actions()));
                PolicyHandOver.handOver(context);
                yield new Outcome.Wait(PolicyHandOver.attachThen(context));
            }
        };
    }
}
