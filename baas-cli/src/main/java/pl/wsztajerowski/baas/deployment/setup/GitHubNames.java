package pl.wsztajerowski.baas.deployment.setup;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * GitHub's name grammar for the owner and repositories setup trusts (X5). Each lands in the operator
 * role's trust policy inside a {@code StringLike} condition, where {@code *} and {@code ?} are
 * wildcards: {@code --github-org '*'} trusted every repository on GitHub, and a repository {@code *}
 * every repository the organisation creates later. Checked for the flags and the walkthrough alike,
 * and wildcards are never allowed: repositories are listed by name.
 */
public final class GitHubNames {

    /** Letters, digits and single hyphens, not leading; at most 39 characters. */
    private static final Pattern OWNER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,38}");
    /** Letters, digits, {@code .}, {@code _} and {@code -}; at most 100 characters. */
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    private GitHubNames() {
    }

    /** What is wrong with these names, or empty when GitHub could hold them. */
    public static Optional<String> problem(String owner, List<String> repositories) {
        if (owner == null || !OWNER.matcher(owner).matches()) {
            return Optional.of("'%s' is not a GitHub organisation or user name (letters, digits and hyphens)."
                .formatted(owner));
        }
        return repositories.stream()
            .filter(repository -> !REPOSITORY.matcher(repository).matches())
            .findFirst()
            .map(repository -> ("'%s' is not a GitHub repository name (letters, digits, '.', '_' and '-'). "
                + "Name each repository: a wildcard would trust repositories you did not name.").formatted(repository));
    }
}
