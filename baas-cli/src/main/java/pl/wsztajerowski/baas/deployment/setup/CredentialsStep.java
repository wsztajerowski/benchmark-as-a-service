package pl.wsztajerowski.baas.deployment.setup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;

import java.util.Objects;

/** Step 2: the deployer's credentials, identified and shown before they are used. */
final class CredentialsStep implements SetupStep {

    static final String NAME = "credentials";
    private static final Logger logger = LoggerFactory.getLogger(CredentialsStep.class);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Outcome run(SetupContext context) {
        boolean askable = context.options.deployerProfile() == null && context.interactive();
        if (!context.profileAsked) {
            context.profileAsked = true;
            String profile = context.options.deployerProfile() != null
                ? context.options.deployerProfile()
                : context.storedProfile;
            if (askable) {
                profile = context.prompter().ask("Deployer AWS profile (empty for the default credentials)", profile);
            }
            context.profile = orNull(profile);
        }
        while (true) {
            try {
                context.caller = context.aws().callerIdentity();
                logger.info("Credentials: account {}, {}", context.caller.account(), context.caller.arn());
                return SetupStep.next();
            } catch (SdkException e) {
                logger.debug("Credentials check failed", e);
                String explanation = explain(context.profile, e);
                if (!askable) {
                    return new Outcome.Wait(explanation);
                }
                // The remedy is as often a mistyped or forgotten profile as a missing login, so the
                // question is the wait: Enter checks the same profile again, a name tries that one.
                logger.warn(explanation);
                var again = context.prompter().answer("Deployer AWS profile (Enter to check again)", context.profile);
                if (again.isEmpty()) {
                    return new Outcome.Stop(1, null);
                }
                context.profile = orNull(again.get());
            }
        }
    }

    static String explain(String profile, SdkException e) {
        String which = profile == null ? "the default credentials" : "profile " + profile;
        String flag = profile == null ? "" : " --profile " + profile;
        return """
            Cannot use %s: %s
              Set up credentials:     aws configure%s   (or: aws configure sso%s)
              An expired SSO session: aws sso login%s""".formatted(which, reason(e), flag, flag, flag);
    }

    /**
     * The SDK's message without the provider chain and the parsed profile file it appends, which
     * run to a screenful and name every profile but the cause. {@code -v} logs the whole message.
     */
    static String reason(SdkException e) {
        String message = Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName());
        if (message.startsWith("Unable to load credentials from any of the providers in the chain")) {
            return "no credentials found";
        }
        int profileFile = message.indexOf(": ProfileFile(");
        return profileFile < 0 ? message : message.substring(0, profileFile);
    }

    private static String orNull(String profile) {
        return profile == null || profile.isBlank() ? null : profile.strip();
    }
}
