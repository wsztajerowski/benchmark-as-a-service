package pl.wsztajerowski.baas.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import pl.wsztajerowski.baas.LoggingMixin;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.config.ConfigService;
import pl.wsztajerowski.baas.infra.AwsClientFactory;
import pl.wsztajerowski.baas.infra.CloudFormationService;

import java.util.concurrent.Callable;

@Command(
    name = "sync",
    mixinStandardHelpOptions = true,
    description = "Adopt an existing installation on this machine."
)
public class ConfigSyncSubcommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(ConfigSyncSubcommand.class);

    @Mixin LoggingMixin loggingMixin;

    /**
     * Required, although the prefix <em>is</em> derivable from the caller's account.
     *
     * <p>A bare {@code baas config sync} on a machine with no local state would adopt whatever
     * installation the currently active credentials imply. In CI that is the worst place for an
     * implicit choice: a workflow federating into an unexpected role, or a leftover
     * {@code AWS_PROFILE}, would bind the machine to another account's installation and fail later,
     * after provisioning, somewhere unrelated. Requiring the name keeps the installation a declared
     * input. It is also needed anyway to reach the dev installation, so defaulting it would only
     * shortcut one of the two cases.
     */
    @Option(names = "--name", required = true,
        description = "Installation to adopt, as printed by `baas admin setup` "
            + "(e.g. baas-123456789012, or baas-123456789012-dev).")
    String name;

    private final ConfigService configService = new ConfigService();

    @Override
    public Integer call() {
        BaasConfig config = configService.load();
        RunCommand.operatorCredentialsWarning(config).ifPresent(logger::warn);

        var factory = new AwsClientFactory(
            config.getAws().getRegion(), config.getAws().resolveOperatorProfile());

        // The stack is read to prove the installation exists, not to harvest values from it.
        // Everything the CLI needs is either derived from the prefix or resolved from this same
        // stack at the moment it is used, so copying outputs into the file would only create a
        // second, staler source of truth.
        try (var cf = factory.cloudFormation()) {
            var outputs = new CloudFormationService(cf).getStackOutputs(name);
            if (outputs.isEmpty()) {
                logger.error("""
                        No installation named '{}' in {}.
                          List them:  aws cloudformation describe-stacks --query \
                    'Stacks[?starts_with(StackName, `baas-`)].StackName'
                          Or create one: baas admin setup""",
                    name, config.getAws().getRegion());
                return 1;
            }
            if (!outputs.containsKey("ResultsTableName")) {
                logger.warn("Stack '{}' reports no ResultsTableName output — it may predate this "
                    + "version of the core template. `baas results` will fail until it is updated.", name);
            }
        }

        config.setPrefix(name);
        configService.save(config);

        logger.info("""
            Adopted installation {}.
              Config: {}
              Bucket: {}
              Table:  {}""",
            name, configService.configFilePath(), config.bucket(), config.resultsTable());
        return 0;
    }
}
