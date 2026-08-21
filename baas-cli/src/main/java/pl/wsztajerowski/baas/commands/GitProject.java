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

    /**
     * {@code --git-common-dir} resolves to the main repository's {@code .git} in a linked worktree
     * and is a no-op for an ordinary clone. {@code --show-toplevel} returns the worktree directory,
     * which attributed a run launched from {@code .claude/worktrees/ddb-phase3} to project
     * {@code ddb-phase3} — a partition {@code baas results} would never look in.
     */
    static String fromCommonDir(String commonDir) {
        if (commonDir == null || commonDir.isBlank()) return null;
        String trimmed = stripTrailingSlashes(commonDir.strip());
        if (trimmed.endsWith("/.git")) {
            trimmed = trimmed.substring(0, trimmed.length() - "/.git".length());
        } else if (trimmed.endsWith(".git")) {
            trimmed = stripTrailingSlashes(trimmed.substring(0, trimmed.length() - ".git".length()));
        }
        return fromToplevel(trimmed);
    }

    private static String stripTrailingSlashes(String value) {
        String trimmed = value;
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed;
    }

    /**
     * Null when {@code workingDir} is not inside a git repository.
     *
     * <p>{@code --path-format=absolute} matters: without it {@code --git-common-dir} can return the
     * relative {@code .git}, which carries no repository name. The {@code --show-toplevel} fallback
     * covers git versions that reject {@code --path-format}.
     */
    static String repositoryName(Path workingDir) {
        String common = gitOutput(workingDir, "git", "rev-parse", "--path-format=absolute", "--git-common-dir");
        return common != null
            ? fromCommonDir(common)
            : fromToplevel(gitOutput(workingDir, "git", "rev-parse", "--show-toplevel"));
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
