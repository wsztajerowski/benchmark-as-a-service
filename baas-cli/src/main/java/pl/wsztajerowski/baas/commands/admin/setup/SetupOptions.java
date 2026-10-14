package pl.wsztajerowski.baas.commands.admin.setup;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * What the command line said. Every prompt has one of these as its answer given in advance: a step
 * asks only when its option is absent.
 */
public record SetupOptions(
    Optional<String> deployment,
    String region,
    String deployerProfile,
    Path policyFile,
    String githubOrg,
    List<String> githubRepos,
    boolean revokeGithubOidc,
    boolean buildImage) {

    public SetupOptions {
        githubRepos = List.copyOf(githubRepos);
    }

    boolean namesFederation() {
        return githubOrg != null || !githubRepos.isEmpty();
    }
}
