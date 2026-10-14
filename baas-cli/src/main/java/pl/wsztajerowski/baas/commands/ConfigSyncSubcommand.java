package pl.wsztajerowski.baas.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import pl.wsztajerowski.baas.BaasApp;
import pl.wsztajerowski.baas.LoggingMixin;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.config.ConfigService;
import pl.wsztajerowski.baas.infra.AwsClientFactory;
import pl.wsztajerowski.baas.infra.CloudFormationService;
import pl.wsztajerowski.baas.infra.OperatorSession;

import java.util.Map;
import java.util.concurrent.Callable;

@Command(
    name = "sync",
    mixinStandardHelpOptions = true,
    description = "Adopt an existing deployment on this machine."
)
public class ConfigSyncSubcommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(ConfigSyncSubcommand.class);

    @Mixin LoggingMixin loggingMixin;

    @Option(names = "--operator-aws-profile",
        description = "AWS CLI profile to take credentials from; the CLI assumes the deployment's "
            + "operator role from it. Checked before it is stored.")
    String operatorProfile;

    @Spec CommandSpec spec;

    private ConfigService configService() {
        return BaasApp.configService(spec);
    }

    /** The AWS calls behind the session. Overridden by tests, which have no AWS. */
    OperatorSession.Aws aws() {
        return OperatorSession.REAL;
    }

    /** The named stack's outputs, empty when it does not exist. Overridden by tests. */
    Map<String, String> stackOutputs(AwsClientFactory factory, String stack) {
        try (var cf = factory.cloudFormation()) {
            return new CloudFormationService(cf).getStackOutputs(stack);
        }
    }

    /**
     * Adopts the deployment {@code --deployment} names, or re-verifies the only one configured.
     * Never derived — not from the account and not from the credentials — although it could be: a
     * bare sync on a machine with no local state would bind it to whatever deployment the active
     * credentials imply. So with nothing configured the name is required.
     *
     * <p>Nothing is copied from AWS except the region, which cannot be derived and never changes;
     * everything else is derived from the name or resolved when used. The checks run before anything
     * is written, so a sync that fails leaves the machine as it was.
     */
    @Override
    public Integer call() {
        BaasConfig config;
        try {
            config = configService().loadForSync();
        } catch (IllegalStateException e) {
            logger.error(e.getMessage());
            return 2;
        }
        String name = config.requirePrefix();
        String recordedRegion = config.getAws().getRegion();
        if (operatorProfile != null) {
            config.getAws().setOperatorProfile(operatorProfile);
        }

        // The region is found again rather than trusted from the file: sync is what re-points a
        // machine at a deployment rebuilt elsewhere. The session proves the credentials reach the
        // operator role before the bucket is asked, under that role.
        config.getAws().setRegion(null);
        var factory = OperatorSession.adopting(config, aws(), System.getenv()).factory();
        String region;
        try {
            region = factory.region();
        } catch (RuntimeException e) {
            config.getAws().setRegion(recordedRegion);
            logger.error(e.getMessage());
            return 1;
        }

        // The stack is read to prove the deployment exists, not to harvest values from it.
        var outputs = stackOutputs(factory, config.stackName());
        if (outputs.isEmpty()) {
            logger.error("""
                    No deployment named '{}': its bucket is in {}, but no stack of that name is.
                      Create the deployment again: baas --deployment {} admin deployment setup --region {}""",
                name, region, name, region);
            return 1;
        }

        config.getAws().setRegion(region);
        configService().save(config);

        logger.info("""
            Adopted deployment {} in {}.
              Config: {}
              Bucket: {}
              Table:  {}""",
            name, region, configService().fileOf(name), config.bucket(), config.resultsTable());
        return 0;
    }
}
