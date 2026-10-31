package pl.wsztajerowski.baas.image;

import java.util.List;

/**
 * The runner AMI as it exists in the account — read back from EC2, not from
 * {@code infra/runner-image.yaml}.
 *
 * <p>{@code imageVersion} and {@code parentAmiId} come from the AMI's own tags rather than the
 * declaration, so {@code baas admin image show} reports what is actually deployed even when the
 * working tree has moved on.
 *
 * <p>{@code jdkIds} are the JDKs a job can select on it, from its {@value #JDKS_TAG} tag, the
 * default first; empty for an image built before JDK selection, which carries no such tag.
 */
public record RunnerImage(
    String amiId,
    String imageVersion,
    String parentAmiId,
    String createdAt,
    List<String> jdkIds
) {
    static final String VERSION_TAG = "baas-image-version";
    static final String PARENT_TAG = "baas-parent-ami";
    /** The image's JDK ids, joined by {@code ,}, the default first — written when the image is published. */
    public static final String JDKS_TAG = "baas-jdks";

    public RunnerImage {
        jdkIds = jdkIds == null ? List.of() : List.copyOf(jdkIds);
    }

    /** An image from before JDK selection. */
    public RunnerImage(String amiId, String imageVersion, String parentAmiId, String createdAt) {
        this(amiId, imageVersion, parentAmiId, createdAt, List.of());
    }

    /** False for an image built before JDK selection: it holds no {@code /opt/jdks}, and accepts no {@code --jdk}. */
    public boolean selectsJdks() {
        return !jdkIds.isEmpty();
    }

    /** The JDK a job without {@code --jdk} runs on. Only for an image that {@link #selectsJdks()}. */
    public String defaultJdk() {
        return jdkIds.getFirst();
    }

    /** {@code corretto-25 (default), temurin-26}, for messages. */
    public String describeJdks() {
        return java.util.stream.IntStream.range(0, jdkIds.size())
            .mapToObj(i -> i == 0 ? jdkIds.get(i) + " (default)" : jdkIds.get(i))
            .collect(java.util.stream.Collectors.joining(", "));
    }

    static List<String> parseJdksTag(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.split(","));
    }
}
