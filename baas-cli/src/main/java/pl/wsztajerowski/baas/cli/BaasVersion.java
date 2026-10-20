package pl.wsztajerowski.baas.cli;

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

    /**
     * The SHA-256 of the runner JAR this CLI was built with, as the build wrote it into
     * {@code /baas-runner.properties}; empty for a build made without the runner beside it. A
     * released CLI launches on {@code releases/<version>/benchmark-runner.jar} only when the object
     * there has exactly this digest, so a runner replaced in the bucket is refused, not executed.
     */
    public static String runnerSha256() {
        try (var in = BaasVersion.class.getResourceAsStream("/baas-runner.properties")) {
            if (in == null) {
                return "";
            }
            var properties = new java.util.Properties();
            properties.load(in);
            return properties.getProperty("runner.sha256", "").strip();
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
}
