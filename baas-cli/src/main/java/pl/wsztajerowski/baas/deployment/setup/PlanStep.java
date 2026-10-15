package pl.wsztajerowski.baas.deployment.setup;

import pl.wsztajerowski.baas.session.DeploymentNames;

import java.util.Objects;

/**
 * Step 7: the plan, confirmed when the run creates a deployment or an answer changed. An update
 * that only carries a newer template goes ahead unconfirmed: predicting it would need
 * {@code cloudformation:GetTemplate}, which the deployer policy does not grant.
 */
final class PlanStep implements SetupStep {

    static final String NAME = "plan";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Outcome run(SetupContext context) {
        boolean answerChanged = context.federationChanged
            || context.existing && context.profile != null && !Objects.equals(context.profile, context.storedProfile);
        if (!context.interactive() || context.planConfirmed || !(context.creating() || answerChanged)) {
            return SetupStep.next();
        }
        context.prompter().console().println(plan(context));
        if (!context.prompter().confirm("Go ahead?", true)) {
            return new Outcome.Stop(1, "Nothing was changed.");
        }
        context.planConfirmed = true;
        return SetupStep.next();
    }

    static String plan(SetupContext context) {
        String action = context.creating()
            ? "create (stack and bucket " + DeploymentNames.of(context.deployment).bucket() + ", table "
                + DeploymentNames.of(context.deployment).resultsTable() + ")"
            : "update";
        String github;
        if (context.revoke) {
            github = context.deployedFederation.map(f -> "stop trusting " + f.describe()).orElse("none");
        } else if (context.federation.isPresent()) {
            github = "trust " + context.federation.get().describe();
        } else {
            github = context.deployedFederation.map(f -> "unchanged (" + f.describe() + ")").orElse("none");
        }
        if (context.providerToCreate) {
            github += "; creates the account's GitHub OIDC provider";
        }
        return """

            Plan
              Account:     %s (as %s)
              Region:      %s
              Deployment:  %s
              Action:      %s
              GitHub:      %s
              Cost:        about $0.20 a month for the runner image's snapshot once it is built;
                           each benchmark job's instance is billed while it runs
            """.formatted(context.caller.account(), context.caller.arn(), context.region, context.deployment,
            action, github);
    }
}
