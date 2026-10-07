package pl.wsztajerowski.baas.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Which deployment names {@code baas admin deployment setup} accepts: the lowest common denominator
 * of every service that carries a name composed from it, and nothing else.
 *
 * <p>Each clause is one service's rule. The CloudFormation stack name must start with a letter. The
 * S3 bucket is the bare prefix: lowercase letters, digits and hyphens, ending alphanumeric, at least
 * three characters, and S3 reserves {@code xn--}, {@code --ol-s3}, {@code --x-s3},
 * {@code --table-s3} (no {@code --} covers all four), {@code sthree-}, {@code amzn-s3-demo-} and
 * {@code -s3alias}. SSM refuses a parameter hierarchy beginning {@code aws} or {@code ssm}, which
 * would break {@code /<prefix>/runner/ami-id} partway through a deploy. The upper bound keeps every
 * composed IAM role name within 64 characters ({@link DeploymentNames#MAX_PREFIX_LENGTH}).
 *
 * <p>Deliberately absent: a check that a {@code baas-<12 digits>} name matches the caller's account.
 * A misleading name is the user's decision, not a defect.
 *
 * <p>Only setup validates. Every other command resolves a name against the configured deployments,
 * where an unconfigured name is an unknown deployment rather than an invalid one.
 */
public final class DeploymentNameRule {

    static final int MIN_LENGTH = 3;

    private static final Pattern SHAPE = Pattern.compile("^[a-z][a-z0-9-]*[a-z0-9]$");
    /** Reserved name starts, each with the service that reserves it. */
    private static final Map<String, String> RESERVED_STARTS = Map.of(
        "aws", "SSM", "ssm", "SSM", "sthree-", "S3", "amzn-s3-demo-", "S3");
    private static final List<String> RESERVED_ENDS = List.of("-s3alias");

    private DeploymentNameRule() {
    }

    /** Why {@code name} cannot name a deployment, or empty when it can. */
    public static Optional<String> violation(String name) {
        if (name.length() < MIN_LENGTH) {
            return Optional.of("must be at least %d characters (S3 bucket names)".formatted(MIN_LENGTH));
        }
        if (name.length() > DeploymentNames.MAX_PREFIX_LENGTH) {
            return Optional.of("must be at most %d characters, or the role name %s exceeds IAM's 64"
                .formatted(DeploymentNames.MAX_PREFIX_LENGTH, DeploymentNames.of(name).imageBuildRole()));
        }
        if (!SHAPE.matcher(name).matches()) {
            return Optional.of("must start with a lowercase letter, contain only lowercase letters, digits "
                + "and hyphens, and end with a letter or digit (CloudFormation stack and S3 bucket names)");
        }
        if (name.contains("--")) {
            return Optional.of("must not contain \"--\" (reserved by S3)");
        }
        for (var reserved : RESERVED_STARTS.entrySet()) {
            if (name.startsWith(reserved.getKey())) {
                return Optional.of("must not start with \"%s\" (reserved by %s)"
                    .formatted(reserved.getKey(), reserved.getValue()));
            }
        }
        for (String end : RESERVED_ENDS) {
            if (name.endsWith(end)) {
                return Optional.of("must not end with \"%s\" (reserved by S3)".formatted(end));
            }
        }
        return Optional.empty();
    }
}
