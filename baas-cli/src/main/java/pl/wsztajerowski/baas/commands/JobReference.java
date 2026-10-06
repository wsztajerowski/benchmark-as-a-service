package pl.wsztajerowski.baas.commands;

import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * A job named on the command line: either its job id, or a literal S3 result path. Shared by
 * {@code baas download} and {@code baas env diff}, so the two accept the same arguments and
 * resolve them the same way.
 */
final class JobReference {

    private static final Pattern JOB_ID = Pattern.compile("\\d{8}T\\d{9}Z-[0-9a-f]{8}");

    private JobReference() {
    }

    /**
     * A path is never a job identifier and a job identifier never contains a slash, so the two
     * argument shapes cannot be confused. The path branch is what keeps every job stored before
     * the unified layout retrievable: those keep their original path, and nothing reconstructs it.
     */
    static boolean looksLikeJobId(String argument) {
        return argument != null && JOB_ID.matcher(argument).matches();
    }

    /**
     * The job's result path, without a trailing slash, or {@code null} when a job id names no
     * stored job. A job id is looked up with {@code byJobId} (the stored {@code resultPath},
     * through {@code jobId-index}), which is called only for a job id. Every job since job
     * items exist resolves through its job item, failed and never-launched ones included; an
     * older job resolves through its measurements, so only an older job that stored none needs
     * its result path.
     */
    static String resolve(String argument, UnaryOperator<String> byJobId) {
        String path = looksLikeJobId(argument) ? byJobId.apply(argument) : argument;
        if (path == null) {
            return null;
        }
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    /** What to say when a job id resolves to nothing. */
    static String noSuchJob(String jobId) {
        return "No job found with id '" + jobId + "'. Check the id with `baas jobs list`. A job "
            + "recorded before job items existed that stored no measurement has no index entry; "
            + "pass its result path (jobs/<project>/<jobId>) instead.";
    }
}
