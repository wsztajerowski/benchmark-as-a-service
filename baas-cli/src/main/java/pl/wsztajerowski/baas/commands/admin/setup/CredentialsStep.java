package pl.wsztajerowski.baas.commands.admin.setup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;

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
        if (!context.profileAsked) {
            context.profileAsked = true;
            String profile = context.options.deployerProfile() != null
                ? context.options.deployerProfile()
                : context.storedProfile;
            if (context.options.deployerProfile() == null && context.interactive()) {
                profile = context.prompter().ask("Deployer AWS profile (empty for the default credentials)", profile);
            }
            context.profile = profile == null || profile.isBlank() ? null : profile.strip();
        }
        try {
            context.caller = context.aws().callerIdentity();
        } catch (SdkException e) {
            return new Outcome.Wait(explain(context.profile, e));
        }
        logger.info("Credentials: account {}, {}", context.caller.account(), context.caller.arn());
        return SetupStep.next();
    }

    static String explain(String profile, SdkException e) {
        String which = profile == null ? "the default credentials" : "profile " + profile;
        String flag = profile == null ? "" : " --profile " + profile;
        return """
            Cannot use %s: %s
              Set up credentials:     aws configure%s   (or: aws configure sso%s)
              An expired SSO session: aws sso login%s""".formatted(which, e.getMessage(), flag, flag, flag);
    }
}
