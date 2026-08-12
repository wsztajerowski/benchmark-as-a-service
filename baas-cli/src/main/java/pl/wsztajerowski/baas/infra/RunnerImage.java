package pl.wsztajerowski.baas.infra;

/**
 * The runner AMI as it exists in the account — read back from EC2, not from
 * {@code infra/runner-image.yaml}.
 *
 * <p>{@code imageVersion} and {@code parentAmiId} come from the AMI's own tags rather than the
 * declaration, so {@code baas admin image} reports what is actually deployed even when the
 * working tree has moved on.
 */
public record RunnerImage(
    String amiId,
    String imageVersion,
    String parentAmiId,
    String createdAt
) {
    static final String VERSION_TAG = "baas-image-version";
    static final String PARENT_TAG = "baas-parent-ami";
}
