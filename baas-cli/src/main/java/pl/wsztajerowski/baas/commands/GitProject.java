package pl.wsztajerowski.baas.commands;

import java.nio.file.Path;

/**
 * The project name, derived once for every command that needs it.
 *
 * <p>{@code baas run} writes to the partition this names and {@code baas results} reads from it, so
 * the two deriving it even slightly differently would send reads at a partition nothing was ever
 * written to — and that presents as "no results", not as an error.
 */
final class GitProject {

    private GitProject() {}

    /** Split out from the git call so the parsing is unit-testable without a repository. */
    static String fromToplevel(String toplevel) {
        if (toplevel == null || toplevel.isBlank()) return null;
        String trimmed = toplevel.strip();
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        int slash = trimmed.lastIndexOf('/');
        String name = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        return name.isEmpty() ? null : name;
    }

    /** Null when {@code workingDir} is not inside a git repository. */
    static String repositoryName(Path workingDir) {
        return fromToplevel(gitOutput(workingDir, "git", "rev-parse", "--show-toplevel"));
    }

    static String gitOutput(Path workingDir, String... args) {
        try {
            var pb = new ProcessBuilder(args).redirectErrorStream(true).directory(workingDir.toFile());
            var proc = pb.start();
            String out = new String(proc.getInputStream().readAllBytes()).trim();
            return proc.waitFor() == 0 ? out : null;
        } catch (Exception e) {
            return null;
        }
    }
}
