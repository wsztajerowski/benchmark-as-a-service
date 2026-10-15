package pl.wsztajerowski.baas.commands.admin.setup;
import pl.wsztajerowski.baas.infra.AwsErrors;

import pl.wsztajerowski.baas.config.CoreStack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.baas.infra.CloudFormationService;
import pl.wsztajerowski.baas.infra.DeployerPreflight;
import software.amazon.awssdk.services.cloudformation.model.StackStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Step 8: create or update the stack. Waits first for any operation still running on it — an
 * interrupted run's — since CloudFormation refuses an update sent then.
 */
final class StackStep implements SetupStep {

    static final String NAME = "stack";
    private static final Logger logger = LoggerFactory.getLogger(StackStep.class);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Outcome run(SetupContext context) throws InterruptedException {
        String stack = context.deployment;
        SetupAws aws = context.aws();
        try {
            Optional<StackStatus> status = aws.awaitSettled(stack);
            if (context.providerToCreate) {
                aws.createOidcProvider();
                context.providerToCreate = false;
                context.providerCreated = true;
                logger.info("Created the account's GitHub OIDC provider {}", SetupAws.providerArn(context.caller.account()));
            }
            Map<String, String> parameters = new LinkedHashMap<>();
            parameters.put(CoreStack.PARAM_RESOURCE_NAME_PREFIX, stack);
            boolean rolledBack = status.isPresent() && status.get() == StackStatus.ROLLBACK_COMPLETE;
            if (status.isEmpty() || rolledBack) {
                if (rolledBack) {
                    logger.info("Stack {} is in ROLLBACK_COMPLETE: an earlier create failed and left nothing behind. "
                        + "Deleting it before creating it again.", stack);
                    aws.deleteStack(stack);
                }
                parameters.putAll(GitHubStep.forCreate(context));
                // The same plan `baas admin image build` submits. Letting the template's placeholder
                // defaults stand would register a no-op component at a version, and Image Builder
                // would then refuse the real one at that same version.
                parameters.putAll(aws.imageParameters(Map.of()));
                aws.submitCreate(stack, parameters);
                context.created = true;
                // Before the wait, not after: an interrupted create must still leave a file naming
                // the stack, or a re-run under another name builds a second deployment and the
                // first is out of teardown's reach.
                DoneStep.save(context);
                aws.awaitCreate(stack);
            } else {
                // Only what this run owns is sent. Federation unchanged is carried forward, and the
                // image is `admin image build`'s: everything it deployed is kept, and only what the
                // stack lacks gets a value.
                parameters.putAll(GitHubStep.forUpdate(context));
                parameters.putAll(aws.imageParameters(aws.stackParameters(stack)));
                context.stackChanged = aws.update(stack, parameters);
            }
        } catch (CloudFormationService.StackOperationException e) {
            if (!e.permissionFailure()) {
                return new Outcome.Stop(1, e.getMessage());
            }
            logger.error(e.getMessage());
            return permissionFailure(context);
        } catch (RuntimeException e) {
            if (!AwsErrors.isAccessDenied(e)) {
                throw e;
            }
            logger.error("{}\n\nThis identity is missing a permission `baas admin deployment setup` needs.", e.getMessage());
            return permissionFailure(context);
        }
        context.operatorRoleArn = aws.stackOutputs(stack).getOrDefault(CoreStack.OUTPUT_OPERATOR_ROLE_ARN, "");
        return SetupStep.next();
    }

    /**
     * The permission check could not run, or covered too little: the policy is handed over as at
     * the permission step, and the run resumes there. The plan stays confirmed.
     */
    static Outcome permissionFailure(SetupContext context) {
        PolicyHandOver.handOver(context);
        return new Outcome.Wait(PolicyHandOver.attachThen(context), PermissionsStep.NAME);
    }
}
