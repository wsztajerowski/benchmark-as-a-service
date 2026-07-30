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

    @Option(names = "--for-arn",
        description = "Render for another identity's ARN instead of the current caller — lets an "
            + "administrator prepare the policy without the user running anything.")
    String forArn;

    private final ConfigService configService = new ConfigService();

    @Override
    public Integer call() throws Exception {
        var renderer = new DeployerPolicyRenderer();
        var config = configService.load();
        var factory = new AwsClientFactory(config.getAws().getRegion(), config.getAws().getProfile());

        String callerArn;
        String accountId;
        if (forArn != null) {
            // Take the account from the ARN itself, not the caller's — an administrator may be
            // preparing this from a different account than the one the policy is for.
            callerArn = forArn;
            accountId = accountOf(forArn);
        } else {
            try (var sts = factory.sts()) {
                var identity = sts.getCallerIdentity();
                accountId = identity.account();
                callerArn = identity.arn();
            }
        }

        String prefix = SetupCommand.computePrefix(callerArn);
        logger.info("Policy for {} (prefix {}). Attach it as a customer-managed policy — "
            + "see infra/README.md.", callerArn, prefix);
        // Payload, so stdout: `baas admin deployer-policy > policy.json` has to stay clean.
        System.out.println(renderer.render(accountId, config.getAws().getRegion(), prefix));
        return 0;
    }

    /** {@code arn:aws:iam::123456789012:user/alice} — the account is the fifth colon-separated field. */
    private static String accountOf(String arn) {
        String[] fields = arn.split(":", 6);
        if (fields.length < 6 || !fields[4].matches("\\d{12}")) {
            throw new IllegalArgumentException(
                "--for-arn must be a full IAM ARN carrying an account ID, e.g. "
                    + "arn:aws:iam::123456789012:user/alice — got: " + arn);
        }
        return fields[4];
    }
}
