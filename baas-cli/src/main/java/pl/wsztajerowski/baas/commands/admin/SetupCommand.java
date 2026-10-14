package pl.wsztajerowski.baas.commands.admin;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;
import pl.wsztajerowski.baas.BaasApp;
import pl.wsztajerowski.baas.LoggingMixin;
import pl.wsztajerowski.baas.commands.admin.setup.AwsSetupAws;
import pl.wsztajerowski.baas.commands.admin.setup.SetupAws;
import pl.wsztajerowski.baas.commands.admin.setup.SetupOptions;
import pl.wsztajerowski.baas.commands.admin.setup.SetupSteps;
import pl.wsztajerowski.baas.console.Console;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A guided walkthrough on a terminal, the same steps unattended otherwise: which deployment,
 * credentials, name and region, deployer permissions, names, GitHub Actions, plan, stack, operator
 * role, runner image, done. Each step checks the real state first, so a re-run continues where the
 * last one stopped and nothing records progress. See {@link SetupSteps}.
 */
@Command(
    name = "setup",
    mixinStandardHelpOptions = true,
    description = {
        "Create or update a deployment, step by step: credentials, the deployer policy, the stack, "
            + "GitHub Actions and the runner image.",
        "On a terminal it asks, showing each default, and waits at any step you must do outside baas. "
            + "Without one, or with --non-interactive, it takes every answer from the options and stops "
            + "at the first such step."
    }
)
public class SetupCommand implements Callable<Integer> {

    @Mixin LoggingMixin loggingMixin;

    @Option(names = "--region", description = "AWS region of a new deployment (default: eu-central-1). Cannot change later.")
    String region;

    @Option(names = "--deployer-aws-profile",
        description = "AWS CLI profile with the deployer policy; stored for every `baas admin` command once "
            + "setup has evidence it can deploy.")
    String deployerProfile;

    @Option(names = "--policy-file",
        description = "Also write the deployer policy rendered for this deployment to this file, "
            + "then carry on as usual. The file is what an IAM administrator attaches.")
    Path policyFile;

    @Option(names = "--non-interactive",
        description = "Ask nothing, even on a terminal: take every answer from the options and stop with exit 1 "
            + "at a step you must do outside baas.")
    boolean nonInteractive;

    @Option(names = "--build-image",
        description = "Build the runner image when it is missing or behind, without asking (~15 minutes). "
            + "Unattended, setup never builds without it.")
    boolean buildImage;

    // ─── GitHub OIDC federation ──────────────────────────────────────────────────
    //
    // These set the federated trust on BaasCliOperatorRole. Omitting them on a later setup leaves
    // the deployed trust untouched rather than dropping it: the update path carries every unnamed
    // parameter forward with UsePreviousValue. That is why revocation needs its own gesture —
    // nothing should be able to cut CI's access to a deployment by accident.
    //
    // No BaasConfig field backs any of them. The deployed stack's parameters are the single
    // source of truth for what the trust policy says; a second copy in the deployment's file would
    // drift, and would make "whose laptop ran setup last" part of whether CI keeps working.

    @Option(names = "--github-org",
        description = "GitHub organisation or user whose workflows may assume the operator role.")
    String githubOrg;

    @Option(names = "--github-repo", split = ",",
        description = "Repository name allowed to assume the operator role. Repeatable, or "
            + "comma-separated; one deployment can serve several repositories.")
    List<String> githubRepos = new ArrayList<>();

    @Option(names = "--revoke-github-oidc",
        description = "Remove the federated trust statement. The only way an update removes it — "
            + "omitting the setting options carries the deployed values forward instead.")
    boolean revokeGithubOidc;

    @Spec CommandSpec spec;

    /** Replaced in tests; reads one answer from the terminal, {@code null} once input is closed. */
    Supplier<String> answerReader = () -> System.console() == null ? null : System.console().readLine();
    /** Replaced in tests. */
    BiFunction<String, String, SetupAws> awsFactory = AwsSetupAws::new;
    /** Replaced in tests, to exercise the interactive paths without a terminal. */
    Function<PrintWriter, Console> consoleFor = Console::of;

    @Override
    public Integer call() throws Exception {
        // Before anything is loaded, resolved or deployed.
        validateFederationOptions();
        Console console = consoleFor.apply(spec.commandLine().getOut());
        var options = new SetupOptions(BaasApp.deployment(spec), region, deployerProfile, policyFile,
            githubOrg, githubRepos, revokeGithubOidc, buildImage);
        return SetupSteps.run(options, BaasApp.configService(spec), console,
            console.interactive() && !nonInteractive, answerReader, awsFactory);
    }

    /**
     * Naming the organisation without a repository, or the reverse, would deploy a condition that
     * silently evaluates false — a setup that reports success and leaves CI with no access.
     * Rejected outright, the same stance {@code baas run} takes on a {@code --tag} for a reserved key.
     */
    void validateFederationOptions() {
        boolean anySetting = githubOrg != null || !githubRepos.isEmpty();
        if (revokeGithubOidc && anySetting) {
            throw new ParameterException(spec.commandLine(),
                "--revoke-github-oidc cannot be combined with --github-org or --github-repo. "
                    + "Revoking and setting at once has no sensible precedence. Nothing was deployed.");
        }
        if (!anySetting) {
            return;
        }
        List<String> missing = new ArrayList<>();
        if (githubOrg == null) missing.add("--github-org");
        if (githubRepos.isEmpty()) missing.add("--github-repo");
        if (!missing.isEmpty()) {
            throw new ParameterException(spec.commandLine(),
                "GitHub federation needs both --github-org and --github-repo; missing "
                    + String.join(", ", missing)
                    + ". A partial set deploys a trust condition that is always false, so the "
                    + "stack would report success while continuous integration has no access. "
                    + "Nothing was deployed.");
        }
    }
}
