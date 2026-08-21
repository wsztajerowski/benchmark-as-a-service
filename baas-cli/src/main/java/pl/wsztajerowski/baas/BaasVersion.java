package pl.wsztajerowski.baas;

/**
 * The CLI's own released version, which is what pins the runner JAR a run executes.
 *
 * <p>A reactor build has no released version, and there is deliberately no fallback: two
 * provisioning paths would produce silently incomparable results, the same reason {@code baas run}
 * refuses to launch without a built AMI.
 */
public final class BaasVersion {

    public static final String PLACEHOLDER = "0.0.0-semantically-released";

    private BaasVersion() {
    }

    public static String current() {
        String version = BaasVersion.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? PLACEHOLDER : version;
    }

    public static boolean isReleased() {
        return isReleased(current());
    }

    static boolean isReleased(String version) {
        return version != null && !version.isBlank() && !PLACEHOLDER.equals(version);
    }
}
