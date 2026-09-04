package pl.wsztajerowski.baas.model;

/**
 * The only place a run's S3 prefix is constructed — the object-store counterpart of
 * {@link ResultKeys}. A hand-built prefix does not fail to compile; it points at nothing, and that
 * presents as an empty download rather than as an error.
 *
 * <p>{@code project} stays in the path even though the id alone is unique: the bucket is genuinely
 * multi-project (its name derives from a hash of the caller ARN), and the segment is the one piece
 * of identity a run keeps when it dies before writing any tag.
 *
 * <p>{@code releases/}, not {@code runner/} — a prefix one character from {@code runs/} would need
 * disambiguating in every listing, and {@code releases/} also states that the artifact is immutable.
 */
public final class RunLayout {

    public static final String RUNS_PREFIX = "runs";
    public static final String RELEASES_PREFIX = "releases";
    public static final String INPUT_SEGMENT = "input";
    public static final String RUNNER_JAR_NAME = "benchmark-runner.jar";
    public static final String BENCHMARK_JAR_NAME = "benchmark.jar";
    public static final String RUNNER_JAR_OVERRIDE_NAME = "runner.jar";

    private RunLayout() {
    }

    public static String runPrefix(String project, String runId) {
        require(project, "project");
        require(runId, "runId");
        return RUNS_PREFIX + "/" + project + "/" + runId;
    }

    public static String inputPrefix(String project, String runId) {
        return runPrefix(project, runId) + "/" + INPUT_SEGMENT;
    }

    public static String benchmarkJarKey(String project, String runId) {
        return inputPrefix(project, runId) + "/" + BENCHMARK_JAR_NAME;
    }

    /**
     * A {@code --runner-jar} override stays per-run under the run's own {@code input/}, so
     * {@link #RELEASES_PREFIX} holds released, immutable artifacts only.
     */
    public static String runnerJarOverrideKey(String project, String runId) {
        return inputPrefix(project, runId) + "/" + RUNNER_JAR_OVERRIDE_NAME;
    }

    public static String runnerJarKey(String version) {
        require(version, "version");
        return RELEASES_PREFIX + "/" + version + "/" + RUNNER_JAR_NAME;
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A run prefix needs a non-blank " + name + ".");
        }
    }
}
