package pl.wsztajerowski.baas.commands;

import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * A job named on the command line: its job id, and nothing else. Shared by {@code jobs show},
 * {@code jobs diff} and {@code jobs download}, so they accept the same argument and resolve it the
 * same way. A literal result path used to be accepted too, for jobs stored before job items or the
 * unified layout; none exist since the deployment was rebuilt, so the second shape went.
 */
final class JobReference {

    private static final Pattern JOB_ID = Pattern.compile("\\d{8}T\\d{9}Z-[0-9a-f]{8}");

    private JobReference() {
    }

    static boolean looksLikeJobId(String argument) {
        return argument != null && JOB_ID.matcher(argument).matches();
    }

    /** Why the argument is not a job id, or {@code null} when it is one. Checked before any AWS call. */
    static String notAJobId(String argument) {
        if (looksLikeJobId(argument)) {
            return null;
        }
        return "'" + argument + "' is not a job id. Pass the id `baas run` printed and `baas jobs list` "
            + "shows, e.g. 20260820T174432812Z-a3f9c21b.";
    }

    /**
     * The job's result path, without a trailing slash, or {@code null} when the id names no stored
     * job. Looked up with {@code byJobId} — the stored {@code resultPath}, through
     * {@code jobId-index} — never reconstructed.
     */
    static String resolve(String jobId, UnaryOperator<String> byJobId) {
        String path = byJobId.apply(jobId);
        if (path == null) {
            return null;
        }
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    /** What to say when a job id resolves to nothing. */
    static String noSuchJob(String jobId) {
        return "No job found with id '" + jobId + "'. Check the id with `baas jobs list`.";
    }
}
