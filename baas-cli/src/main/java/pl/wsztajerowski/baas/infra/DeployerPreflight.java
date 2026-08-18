package pl.wsztajerowski.baas.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.iam.IamClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Checks, before {@code baas admin setup} spends any time, that the caller can actually do the
 * job — and when it cannot, prints the exact policy to attach.
 *
 * <p>This is an affordance, not a control: anyone holding the deployer policy can call IAM
 * directly and never run this command. Nothing here constrains a deployer, by design — see the
 * deployer-privilege row in CLAUDE.md's accepted risks.
 */
public class DeployerPreflight {

    private static final Logger logger = LoggerFactory.getLogger(DeployerPreflight.class);

    /** Error codes the SDK uses for "you are not allowed to do this", across services. */
    private static final Set<String> ACCESS_DENIED_CODES =
        Set.of("AccessDenied", "AccessDeniedException", "UnauthorizedOperation");

    private final IamClient iam;

    public DeployerPreflight(IamClient iam) {
        this.iam = iam;
    }

    public static boolean isAccessDenied(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof AwsServiceException aws && aws.awsErrorDetails() != null
                && ACCESS_DENIED_CODES.contains(aws.awsErrorDetails().errorCode())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Best-effort check that the deployer policy is attached, using {@code SimulatePrincipalPolicy}.
     *
     * <p>Only unconditioned, resource-scoped actions are simulated. The EC2 statement carries an
     * {@code aws:RequestedRegion} condition and is left out: the simulator has no such key in its
     * request context, so it would report a denial that will not happen, and a false alarm here is
     * worse than no check at all.
     *
     * @return actions the simulator reported as denied; empty when it could not run at all
     */
    public List<String> simulateCriticalActions(String callerArn, String accountId,
                                                String region, String prefix) {
        Optional<String> principal = resolvePrincipalArn(callerArn);
        if (principal.isEmpty()) {
            return List.of();
        }

        Map<String, String> actionToResource = criticalActionsToResources(accountId, region, prefix);

        try {
            var denied = actionToResource.entrySet().stream()
                .flatMap(entry -> iam.simulatePrincipalPolicy(request -> request
                        .policySourceArn(principal.get())
                        .actionNames(entry.getKey())
                        .resourceArns(entry.getValue()))
                    .evaluationResults().stream())
                .filter(result -> !"allowed".equals(result.evalDecisionAsString()))
                .map(result -> result.evalActionName())
                .toList();
            logger.debug("Simulated {} action(s) for {}; denied: {}",
                actionToResource.size(), principal.get(), denied);
            return denied;
        } catch (AwsServiceException e) {
            // A caller without iam:SimulatePrincipalPolicy simply gets no pre-check.
            logger.debug("Cannot simulate policies ({}) — relying on the real calls to fail instead.",
                e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : e.getMessage());
            return List.of();
        }
    }

    /**
     * The critical-action-to-resource map simulated above, extracted so it can be asserted on
     * directly without an {@link IamClient} — every entry here has to stay unconditioned and
     * resource-scoped, the shape {@code SimulatePrincipalPolicy} can actually answer.
     */
    static Map<String, String> criticalActionsToResources(String accountId, String region, String prefix) {
        Map<String, String> actionToResource = new LinkedHashMap<>();
        actionToResource.put("cloudformation:CreateStack",
            "arn:aws:cloudformation:%s:%s:stack/baas-%s/*".formatted(region, accountId, prefix));
        actionToResource.put("s3:CreateBucket", "arn:aws:s3:::baas-" + prefix);
        actionToResource.put("ssm:PutParameter",
            "arn:aws:ssm:%s:%s:parameter/%s/mongo/connection-string".formatted(region, accountId, prefix));
        actionToResource.put("iam:GetRole",
            "arn:aws:iam::%s:role/%s-runner-role".formatted(accountId, prefix));
        actionToResource.put("iam:CreateRole",
            "arn:aws:iam::%s:role/%s-operator-role".formatted(accountId, prefix));
        // dynamodb:CreateTable is unconditioned and resource-scoped, same as the five above — a
        // deployer running with a stale attached policy previously passed preflight only to have
        // the real stack update fail partway on this action and roll back.
        actionToResource.put("dynamodb:CreateTable",
            "arn:aws:dynamodb:%s:%s:table/baas-%s-results".formatted(region, accountId, prefix));
        return actionToResource;
    }

    /**
     * SimulatePrincipalPolicy wants a role or user ARN; {@code sts:GetCallerIdentity} returns a
     * session ARN for role-based callers. The role's real ARN can carry a path (SSO roles live
     * under {@code role/aws-reserved/sso.amazonaws.com/<region>/}), which cannot be reconstructed
     * from the session ARN — so it is looked up rather than guessed, and simulation is skipped
     * when the lookup is not permitted.
     */
    private Optional<String> resolvePrincipalArn(String callerArn) {
        if (callerArn.contains(":user/") || callerArn.contains(":role/")) {
            return Optional.of(callerArn);
        }
        int roleStart = callerArn.indexOf(":assumed-role/");
        if (roleStart < 0) {
            logger.debug("Unrecognised caller ARN shape {} — skipping simulation.", callerArn);
            return Optional.empty();
        }
        String remainder = callerArn.substring(roleStart + ":assumed-role/".length());
        String roleName = remainder.contains("/") ? remainder.substring(0, remainder.indexOf('/')) : remainder;
        try {
            return Optional.of(iam.getRole(request -> request.roleName(roleName)).role().arn());
        } catch (AwsServiceException e) {
            logger.debug("Cannot resolve role {} to an ARN ({}) — skipping simulation.",
                roleName, e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : e.getMessage());
            return Optional.empty();
        }
    }
}
