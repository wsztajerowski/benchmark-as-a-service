package pl.wsztajerowski.baas.deployment.setup;

import java.util.ArrayList;
import java.util.List;

/**
 * The interactive run's answers, rendered as the non-interactive command that gives them in
 * advance. Prompts and options are one set of inputs, so this is a plain rendering — and a test
 * parses it back, which also fails the build if a prompt ever gains no option.
 */
final class RepeatCommand {

    private RepeatCommand() {}

    static String render(SetupContext context) {
        return String.join(" ", tokens(context));
    }

    static List<String> tokens(SetupContext context) {
        List<String> tokens = new ArrayList<>(List.of("baas", "--deployment", context.deployment,
            "admin", "deployment", "setup", "--non-interactive"));
        if (context.created) {
            // The region is chosen once; on an update the file already holds it.
            tokens.addAll(List.of("--region", context.region));
        }
        if (context.profile != null) {
            tokens.addAll(List.of("--deployer-aws-profile", context.profile));
        }
        if (context.federationChanged) {
            if (context.revoke) {
                tokens.add("--revoke-github-oidc");
            } else {
                context.federation.ifPresent(f -> tokens.addAll(List.of(
                    "--github-org", f.org(), "--github-repo", String.join(",", f.repos()))));
            }
        }
        if (context.options.policyFile() != null) {
            tokens.addAll(List.of("--policy-file", context.options.policyFile().toString()));
        }
        if (context.imageBuilt) {
            tokens.add("--build-image");
        }
        return tokens;
    }
}
