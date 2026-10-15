package pl.wsztajerowski.baas.deployment.setup;

import pl.wsztajerowski.baas.aws.AwsErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Runs the steps in order. Knows nothing of AWS: what a step found and what to do about it is the
 * step's; when to ask again, when to stop and where to go back to is this.
 *
 * <p>A step is checked again only when the user presses Enter, never on a timer: the permission
 * check is fourteen calls to an API IAM throttles hard.
 */
final class SetupWalkthrough {

    private static final Logger logger = LoggerFactory.getLogger(SetupWalkthrough.class);

    private final List<SetupStep> steps;

    SetupWalkthrough(List<SetupStep> steps) {
        this.steps = steps;
    }

    /** @return the exit code */
    int run(SetupContext context) throws Exception {
        int index = 0;
        while (index < steps.size()) {
            SetupStep step = steps.get(index);
            logger.debug("Setup step: {}", step.name());
            switch (outcome(step, context)) {
                case SetupStep.Outcome.Next ignored -> index++;
                case SetupStep.Outcome.Stop stop -> {
                    if (stop.message() != null && !stop.message().isBlank()) {
                        logger.error(stop.message());
                    }
                    return stop.exitCode();
                }
                case SetupStep.Outcome.GoTo goTo -> index = indexOf(goTo.step());
                case SetupStep.Outcome.Wait wait -> {
                    logger.warn(wait.explanation());
                    if (!context.interactive()) {
                        return 1;
                    }
                    if (!context.prompter().pause("Press Enter to check again, or Ctrl+C to stop.")) {
                        return 1;
                    }
                    if (wait.resumeAt() != null) {
                        index = indexOf(wait.resumeAt());
                    }
                }
            }
        }
        return 0;
    }

    /**
     * A step's outcome, with any access denial after the policy is rendered turned into the policy
     * hand-over. The permission check is a convenience: an identity it could not check, or a
     * statement it cannot evaluate, is found only when a call is refused — at whichever step makes
     * that call — and a bare AccessDenied there is the dead end the walkthrough exists to remove.
     */
    private static SetupStep.Outcome outcome(SetupStep step, SetupContext context) throws Exception {
        try {
            return step.run(context);
        } catch (RuntimeException e) {
            if (context.policy == null || !AwsErrors.isAccessDenied(e)) {
                throw e;
            }
            logger.error("{}\n\nThis identity is missing a permission `baas admin deployment setup` needs.", e.getMessage());
            return StackStep.permissionFailure(context);
        }
    }

    private int indexOf(String name) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).name().equals(name)) {
                return i;
            }
        }
        throw new IllegalArgumentException("No setup step named " + name);
    }
}
