package pl.wsztajerowski.baas.commands.admin.setup;

import pl.wsztajerowski.baas.config.CoreStack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Step 6: whether GitHub Actions may federate into the operator role, and the account's OIDC
 * provider that federation needs. The provider is only checked here; a missing one is created at
 * the stack step, after the plan is confirmed, so declining the plan changes nothing.
 */
final class GitHubStep implements SetupStep {

    static final String NAME = "github";
    private static final Logger logger = LoggerFactory.getLogger(GitHubStep.class);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Outcome run(SetupContext context) {
        if (!context.federationAsked) {
            context.federationAsked = true;
            context.deployedFederation = context.creating()
                ? Optional.empty()
                : deployed(context.aws().stackParameters(context.deployment));
            decide(context);
        }
        Optional<SetupContext.Federation> effective = context.revoke
            ? Optional.empty()
            : context.federation.or(() -> context.deployedFederation);
        if (effective.isEmpty()) {
            return SetupStep.next();
        }

        String arn = SetupAws.providerArn(context.caller.account());
        return switch (context.aws().oidcProvider(arn)) {
            case SetupAws.OidcProvider.Absent absent -> {
                logger.info("GitHub OIDC provider: absent, so setup will create it ({})", arn);
                context.providerToCreate = true;
                yield SetupStep.next();
            }
            case SetupAws.OidcProvider.Present present when present.clientIds().contains(SetupAws.STS_AUDIENCE) -> {
                logger.info("GitHub OIDC provider: present, reused");
                context.providerToCreate = false;
                yield SetupStep.next();
            }
            case SetupAws.OidcProvider.Present present -> new Outcome.Wait("""
                The account's GitHub OIDC provider does not accept the audience %2$s, which the operator role's
                trust requires. It is account-wide and may serve other projects, so baas never modifies it.
                  For an IAM administrator:
                    aws iam add-client-id-to-open-id-connect-provider --open-id-connect-provider-arn %1$s --client-id %2$s"""
                .formatted(arn, SetupAws.STS_AUDIENCE));
        };
    }

    private static void decide(SetupContext context) {
        var options = context.options;
        if (options.revokeGithubOidc()) {
            context.revoke = true;
            context.federationChanged = context.deployedFederation.isPresent();
            return;
        }
        if (options.namesFederation()) {
            var answer = new SetupContext.Federation(options.githubOrg(), clean(options.githubRepos()));
            context.federation = Optional.of(answer);
            context.federationChanged = !context.deployedFederation.equals(context.federation);
            return;
        }
        if (!context.interactive()) {
            return;
        }
        var prompter = context.prompter();
        if (context.deployedFederation.isPresent()) {
            var current = context.deployedFederation.get();
            prompter.console().println("GitHub Actions: " + current.describe() + " may run benchmarks on this deployment.");
            if (!prompter.confirm("Change that?", false)) {
                return;
            }
            String org = prompter.ask("GitHub organisation or user (type - to stop trusting GitHub)", current.org());
            if ("-".equals(org)) {
                context.revoke = true;
                context.federationChanged = true;
                return;
            }
            String repos = prompter.ask("Repositories, comma-separated", String.join(",", current.repos()));
            setAnswer(context, org, repos);
            return;
        }
        if (!prompter.confirm("Let GitHub Actions workflows run benchmarks on this deployment?", false)) {
            return;
        }
        String org = prompter.ask("GitHub organisation or user", null);
        String repos = prompter.ask("Repositories, comma-separated", null);
        if (org == null || org.isBlank() || repos == null || repos.isBlank()) {
            prompter.console().println("Both are needed; GitHub Actions is left off.");
            return;
        }
        setAnswer(context, org, repos);
    }

    private static void setAnswer(SetupContext context, String org, String repos) {
        var answer = new SetupContext.Federation(org.strip(), clean(Arrays.asList(repos.split(","))));
        context.federation = Optional.of(answer);
        context.federationChanged = !context.deployedFederation.equals(context.federation);
    }

    private static List<String> clean(List<String> repos) {
        return repos.stream().map(String::strip).filter(repo -> !repo.isEmpty()).toList();
    }

    /** The trust the stack declares now, read back from its parameters — the single source of truth. */
    static Optional<SetupContext.Federation> deployed(Map<String, String> parameters) {
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
        return repos.isEmpty() ? Optional.empty() : Optional.of(new SetupContext.Federation(org, repos));
    }

    /**
     * What a create submits: the federation answered, or both parameters explicitly empty — a
     * create cannot use {@code UsePreviousValue}, CloudFormation rejects it for a parameter with no
     * previous value.
     */
    static Map<String, String> forCreate(SetupContext context) {
        return context.federation.isPresent() && !context.revoke
            ? values(context.federation.get())
            : none();
    }

    /**
     * What an update submits: nothing when the answer did not change, so the deployed values are
     * carried forward and a setup run for an unrelated reason cannot revoke CI's access; both
     * empty to revoke, which is the only way an update removes the trust.
     */
    static Map<String, String> forUpdate(SetupContext context) {
        if (context.revoke) {
            return none();
        }
        if (context.federationChanged && context.federation.isPresent()) {
            return values(context.federation.get());
        }
        return Map.of();
    }

    private static Map<String, String> values(SetupContext.Federation federation) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(CoreStack.PARAM_GITHUB_ORG, federation.org());
        values.put(CoreStack.PARAM_GITHUB_REPO, subjectPatterns(federation.org(), federation.repos()));
        return values;
    }

    private static Map<String, String> none() {
        return Map.of(CoreStack.PARAM_GITHUB_ORG, "", CoreStack.PARAM_GITHUB_REPO, "");
    }

    /**
     * One OIDC subject pattern per repository, composed here rather than in the template:
     * CloudFormation cannot iterate a list, and every in-template trick for it relies on
     * {@code Fn::Sub} re-scanning text substituted into it, which it does not do.
     *
     * <p>The trailing {@code :*} admits every workflow and ref in the repository. Fork pull
     * requests cannot obtain an {@code id-token} at all, so it is not reachable from a fork.
     */
    static String subjectPatterns(String org, List<String> repos) {
        return repos.stream()
            .map(String::strip)
            .filter(repo -> !repo.isEmpty())
            .map(repo -> "repo:" + org + "/" + repo + ":*")
            .collect(Collectors.joining(","));
    }
}
