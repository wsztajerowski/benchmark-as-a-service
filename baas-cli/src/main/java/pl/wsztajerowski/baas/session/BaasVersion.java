package pl.wsztajerowski.baas.session;

/**
 * The CLI's own released version, which is what pins the runner JAR a job executes.
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

    /**
     * The repository whose releases this build seeds its runner JAR from: set when the CLI is built
     * ({@code baas.sourceRepo}, the releasing repository in a release), never configured. A fork's
     * release therefore fetches the fork's runner, and no stale setting can pair one build's CLI with
     * another build's runner.
     */
    public static String sourceRepo() {
        try (var in = BaasVersion.class.getResourceAsStream("/baas-build.properties")) {
            if (in == null) {
                throw new IllegalStateException("/baas-build.properties is not on the classpath");
            }
            var properties = new java.util.Properties();
            properties.load(in);
            return properties.getProperty("runner.sourceRepo", "").strip();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public static boolean isReleased() {
        return isReleased(current());
    }

    static boolean isReleased(String version) {
        return version != null && !version.isBlank() && !PLACEHOLDER.equals(version);
    }

    /**
     * Whether {@code version} can be ordered against another: a released, numeric
     * {@code <major>.<minor>.<patch>}. The placeholder is not, and neither is a pre-release such as
     * {@code 8.0.0-rc.1}, which this repository's releases never produce — both are treated as
     * unreleased wherever versions are compared.
     */
    public static boolean comparable(String version) {
        return isReleased(version) && NUMERIC.matcher(version).matches();
    }

    /**
     * Orders two {@code x.y.z} versions numerically — {@code 1.10.0} after {@code 1.9.0}, which a
     * string comparison gets wrong. Callers pass only {@link #comparable} versions, or ones a
     * stack's {@code AllowedPattern} has already shaped.
     */
    public static int compare(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int l = i < left.length ? Integer.parseInt(left[i]) : 0;
            int r = i < right.length ? Integer.parseInt(right[i]) : 0;
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return 0;
    }

    private static final java.util.regex.Pattern NUMERIC =
        java.util.regex.Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+$");
}
