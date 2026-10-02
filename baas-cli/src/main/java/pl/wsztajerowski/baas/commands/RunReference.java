package pl.wsztajerowski.baas.commands;

import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * A run named on the command line: either its run id, or a literal S3 result path. Shared by
 * {@code baas download} and {@code baas env diff}, so the two accept the same arguments and
 * resolve them the same way.
 */
final class RunReference {

    private static final Pattern RUN_ID = Pattern.compile("\\d{8}T\\d{9}Z-[0-9a-f]{8}");

    private RunReference() {
    }

    /**
     * A path is never a run identifier and a run identifier never contains a slash, so the two
     * argument shapes cannot be confused. The path branch is what keeps every run stored before
     * the unified layout retrievable: those keep their original path, and nothing reconstructs it.
     */
    static boolean looksLikeRunId(String argument) {
        return argument != null && RUN_ID.matcher(argument).matches();
    }

    /**
     * The run's result path, without a trailing slash, or {@code null} when a run id names no
     * stored run. A run id is looked up with {@code byRunId} (the item's stored
     * {@code resultPath}, through {@code requestId-index}), which is called only for a run id.
     * A run that failed before storing a measurement has no index entry yet, so its id does not
     * resolve; its result path still does.
     */
    static String resolve(String argument, UnaryOperator<String> byRunId) {
        String path = looksLikeRunId(argument) ? byRunId.apply(argument) : argument;
        if (path == null) {
            return null;
        }
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    /** What to say when a run id resolves to nothing. */
    static String noSuchRun(String runId) {
        return "No run found with id '" + runId + "'. A run that failed before storing a "
            + "measurement has no index entry; pass its result path (runs/<project>/<runId>) instead.";
    }
}
