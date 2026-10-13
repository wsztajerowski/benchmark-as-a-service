package pl.wsztajerowski.baas.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.iam.IamClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Checks, before {@code baas admin deployment setup} spends any time, that the caller can actually do the
 * job — and when it cannot, prints the exact policy to attach.
 *
 * <p>This is an affordance, not a control: anyone holding the deployer policy can call IAM
 * directly and never run this command. Nothing here constrains a deployer, by design — see the
 * deployer-privilege row in CLAUDE.md's accepted risks.
 */
public class DeployerPreflight {

    private static final Logger logger = LoggerFactory.getLogger(DeployerPreflight.class);
    private static final ObjectMapper JSON = new ObjectMapper();

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
     * <p>What is probed is derived from {@code renderedPolicy} itself (see {@link #probes}), so a
     * statement added in a later release is checked in that release. A hand-kept list of
     * "critical" actions used to stand here; three times a release added a statement it did not
     * cover, and a stale policy passed: {@code dynamodb:CreateTable} (a stack update rolled back),
     * {@code iam:UpdateRole} ({@code baas-3q7i7s65} left in UPDATE_ROLLBACK_FAILED) and, in 7.1,
     * {@code sts:AssumeRole}, which let setup pass and then stopped every day-to-day command.
     *
     * <p>One call per action, judged on the action's overall decision. A call naming several
     * actions is refused when they need different authorization information ({@code iam:Get*}
     * beside {@code iam:CreateRole}), and the resource-specific results report {@code implicitDeny}
     * for every resource whose type the action does not apply to, while the overall decision stays
     * {@code allowed} (both found live, setup-prerequisites task 1.1).
     *
     * @return actions the simulator reported as denied, in policy order; empty when it could not
     * run at all
     */
    public List<String> simulate(String callerArn, String renderedPolicy) {
        Optional<String> principal = resolvePrincipalArn(callerArn);
        if (principal.isEmpty()) {
            return List.of();
        }

        List<Probe> probes = probes(renderedPolicy);
        try {
            var denied = probes.stream()
                .filter(probe -> iam.simulatePrincipalPolicy(request -> request
                        .policySourceArn(principal.get())
                        .actionNames(probe.action())
                        .resourceArns(probe.resources()))
                    .evaluationResults().stream()
                    .anyMatch(result -> !"allowed".equals(result.evalDecisionAsString())))
                .map(Probe::action)
                .distinct()
                .toList();
            logger.debug("Simulated {} action(s) for {}; denied: {}", probes.size(), principal.get(), denied);
            return denied;
        } catch (AwsServiceException e) {
            // A caller without iam:SimulatePrincipalPolicy simply gets no pre-check.
            logger.debug("Cannot simulate policies ({}) — relying on the real calls to fail instead.",
                e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : e.getMessage());
            return List.of();
        }
    }

    /** One simulated action, with every resource its statement names. */
    record Probe(String sid, String action, List<String> resources) {
    }

    /**
     * Every action of every {@code Allow} statement that carries no {@code Condition}, each with its
     * statement's resources, verbatim — wildcard action names and pattern resources included, which
     * the simulator matches as IAM does. Conditioned statements are left out: the simulator has no
     * value for {@code aws:RequestedRegion} or {@code iam:AWSServiceName} in its request context, so
     * it would report a denial that will not happen, and a false alarm here is worse than no check.
     */
    static List<Probe> probes(String renderedPolicy) {
        JsonNode statements;
        try {
            statements = JSON.readTree(renderedPolicy).path("Statement");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The rendered deployer policy is not valid JSON", e);
        }
        List<Probe> probes = new ArrayList<>();
        for (JsonNode statement : statements) {
            if (!"Allow".equals(statement.path("Effect").asText()) || statement.has("Condition")) {
                continue;
            }
            List<String> resources = strings(statement.path("Resource"));
            for (String action : strings(statement.path("Action"))) {
                probes.add(new Probe(statement.path("Sid").asText(), action, resources));
            }
        }
        return probes;
    }

    private static List<String> strings(JsonNode node) {
        if (node.isTextual()) {
            return List.of(node.asText());
        }
        List<String> values = new ArrayList<>();
        node.forEach(value -> values.add(value.asText()));
        return values;
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
