package pl.wsztajerowski.baas.deployment.setup;

import pl.wsztajerowski.baas.session.CoreStack;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Map;

/**
 * The GitHub repositories a deployment's stack trusts to run benchmarks, read back from the stack's
 * parameters — the single source of truth, which setup edits and {@code admin deployment show}
 * reports.
 */
public record GitHubTrust(String org, List<String> repos) {

    public GitHubTrust {
        repos = List.copyOf(repos);
    }

    /** Empty when either parameter is blank or no pattern names a repository of the organisation. */
    public static Optional<GitHubTrust> deployed(Map<String, String> parameters) {
        String org = parameters.getOrDefault(CoreStack.PARAM_GITHUB_ORG, "");
        String patterns = parameters.getOrDefault(CoreStack.PARAM_GITHUB_REPO, "");
        if (org.isBlank() || patterns.isBlank()) {
            return Optional.empty();
        }
        String prefix = "repo:" + org + "/";
        List<String> repos = Arrays.stream(patterns.split(","))
            .map(String::strip)
            .filter(pattern -> pattern.startsWith(prefix) && pattern.endsWith(":*"))
            .map(pattern -> pattern.substring(prefix.length(), pattern.length() - 2))
            .toList();
        return repos.isEmpty() ? Optional.empty() : Optional.of(new GitHubTrust(org, repos));
    }
}
