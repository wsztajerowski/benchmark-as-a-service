package pl.wsztajerowski.baas.commands.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import pl.wsztajerowski.baas.LoggingMixin;
import pl.wsztajerowski.baas.config.ConfigService;
import pl.wsztajerowski.baas.infra.AwsClientFactory;
import pl.wsztajerowski.baas.infra.DeployerPolicyRenderer;

import java.util.concurrent.Callable;

@Command(
    name = "deployer-policy",
    mixinStandardHelpOptions = true,
    description = "Print the IAM policy an identity needs before it can run `baas admin setup`."
)
public class DeployerPolicyCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(DeployerPolicyCommand.class);

    @Mixin LoggingMixin loggingMixin;

    /**
     * Replaces {@code --for-arn}. Every resource the policy names now derives from the account, the
     * region and the mode, so "render it for someone else" means "render it for another account".
     * An ARN would also be the wrong thing to ask for: under SSO the caller ARN carries a session
     * name only the target user can print, so an administrator could not supply it anyway.
     */
    @Option(names = "--for-account",
        description = "Render for another AWS account instead of the caller's — lets an "
            + "administrator prepare the policy without the user running anything.")
    String forAccount;

    /**
     * Renders for an installation other than the account's own {@code baas-<accountId>} — a
     * scratch installation deployed by hand for BaaS development, most of all. This only changes
     * what is <em>printed</em>; it grants nothing and {@code baas admin setup} still derives its
     * own name. See infra/README.md.
     */
    @Option(names = "--prefix",
        description = "Render for this installation instead of the account's own "
            + "baas-<accountId> — e.g. a by-hand development installation.")
    String prefix;

    private final ConfigService configService = new ConfigService();

    @Override
    public Integer call() {
        var renderer = new DeployerPolicyRenderer();
        var config = configService.load();
        var factory = new AwsClientFactory(config.getAws().getRegion(), config.getAws().getProfile());

        String accountId;
        if (forAccount != null) {
            accountId = resolveAccount(null);
        } else {
            try (var sts = factory.sts()) {
                accountId = resolveAccount(sts.getCallerIdentity().account());
            }
        }

        String resolved = renderedPrefix(accountId);
        logger.info("Policy for installation {} (account {}). Attach it as a customer-managed "
            + "policy — see infra/README.md.", resolved, accountId);
        // Payload, so stdout: `baas admin deployer-policy > policy.json` has to stay clean.
        System.out.println(renderer.render(accountId, config.getAws().getRegion(), resolved));
        return 0;
    }

    /** {@code --for-account} when given, otherwise the caller's own account. */
    String resolveAccount(String callerAccount) {
        String candidate = forAccount != null ? forAccount : callerAccount;
        if (candidate == null || !candidate.matches("\\d{12}")) {
            throw new IllegalArgumentException(
                "Expected a 12-digit AWS account id, got: " + candidate);
        }
        return candidate;
    }

    /** The same derivation {@code baas admin setup} performs, unless one is named explicitly. */
    String renderedPrefix(String accountId) {
        return prefix != null ? prefix : SetupCommand.computePrefix(accountId);
    }
}
