package pl.wsztajerowski.baas.model;

/**
 * The only place a job's S3 prefix is constructed — the object-store counterpart of
 * {@link pl.wsztajerowski.baas.model.ResultKeys}. A hand-built prefix does not fail to compile; it points at nothing, and that
 * presents as an empty download rather than as an error.
 *
 * <p>{@code project} stays in the path even though the id alone is unique: the bucket is genuinely
 * multi-project (its name derives from a hash of the caller ARN), and the segment is the one piece
 * of identity a job keeps when it dies before writing any tag.
 *
 * <p>{@code releases/}, not {@code runner/} — a prefix one character from {@code jobs/} would need
 * disambiguating in every listing, and {@code releases/} also states that the artifact is immutable.
 */
public final class JobLayout {

    public static final String JOBS_PREFIX = "jobs";
    public static final String RELEASES_PREFIX = "releases";
    public static final String INPUT_SEGMENT = "input";
    public static final String RUNNER_JAR_NAME = "benchmark-runner.jar";
    public static final String BENCHMARK_JAR_NAME = "benchmark.jar";
    public static final String RUNNER_JAR_OVERRIDE_NAME = "runner.jar";
    public static final String LAUNCH_ERROR_NAME = "launch-error.txt";

    // The artifacts a job writes inside its prefix. Three writers (user-data, the runner and the
    // CLI's launch path) and three readers (jobs show, diff and download) name them, so they are
    // spelled here once; user-data receives them as exported variables.
    /** What the job measured on, written by user-data before the benchmark starts. */
    public static final String ENVIRONMENT_NAME = "environment.json";
    /** {@code rpm -qa}, split out of the manifest. */
    public static final String PACKAGES_NAME = "packages.txt";
    /** The instance's boot log, uploaded before it terminates itself. */
    public static final String BOOT_LOG_NAME = "cloud-init-output.log";
    /** JMH's own machine-readable output, verbatim. */
    public static final String RESULT_JSON_NAME = "jmh-result.json";
    /** Where every {@code .log} below the runner's working directory goes, by relative path. */
    public static final String LOGS_SEGMENT = "logs";

    private JobLayout() {
    }

    public static String jobPrefix(String project, String jobId) {
        require(project, "project");
        require(jobId, "jobId");
        return JOBS_PREFIX + "/" + project + "/" + jobId;
    }

    public static String inputPrefix(String project, String jobId) {
        return jobPrefix(project, jobId) + "/" + INPUT_SEGMENT;
    }

    public static String benchmarkJarKey(String project, String jobId) {
        return inputPrefix(project, jobId) + "/" + BENCHMARK_JAR_NAME;
    }

    /**
     * A {@code --runner-jar} override stays per-job under the job's own {@code input/}, so
     * {@link #RELEASES_PREFIX} holds released, immutable artifacts only.
     */
    public static String runnerJarOverrideKey(String project, String jobId) {
        return inputPrefix(project, jobId) + "/" + RUNNER_JAR_OVERRIDE_NAME;
    }

    /**
     * Why the instance request failed, for a job that never launched: there is no instance and so
     * no boot log, and this is what {@code baas jobs download <jobId>} then has to show.
     */
    public static String launchErrorKey(String project, String jobId) {
        return jobPrefix(project, jobId) + "/" + LAUNCH_ERROR_NAME;
    }

    /**
     * One artifact inside a job's result path, as the job item stores that path. Tolerates the
     * trailing slash older items carry, so every reader joins the same way.
     */
    public static String artifactKey(String resultPath, String name) {
        require(resultPath, "result path");
        String base = resultPath.endsWith("/") ? resultPath.substring(0, resultPath.length() - 1) : resultPath;
        return base + "/" + name;
    }

    public static String runnerJarKey(String version) {
        require(version, "version");
        return RELEASES_PREFIX + "/" + version + "/" + RUNNER_JAR_NAME;
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A job prefix needs a non-blank " + name + ".");
        }
    }
}
