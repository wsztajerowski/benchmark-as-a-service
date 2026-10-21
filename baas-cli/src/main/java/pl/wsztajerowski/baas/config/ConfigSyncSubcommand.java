package pl.wsztajerowski.baas.config;

import pl.wsztajerowski.baas.cli.CommandRefusal;
import pl.wsztajerowski.baas.cli.Invocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import pl.wsztajerowski.baas.cli.LoggingMixin;
import pl.wsztajerowski.baas.session.BaasConfig;
import pl.wsztajerowski.baas.aws.AwsClientFactory;
import pl.wsztajerowski.baas.aws.CloudFormationService;
import pl.wsztajerowski.baas.session.OperatorSession;

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
            config = Invocation.configService(spec).loadForSync();
        } catch (IllegalStateException e) {
            throw CommandRefusal.usage(e.getMessage());
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
            throw CommandRefusal.refused(e.getMessage());
        }

        // The stack is read to prove the deployment exists, not to harvest values from it.
        var outputs = stackOutputs(factory, config.stackName());
        if (outputs.isEmpty()) {
            throw CommandRefusal.refused("""
                    No deployment named '%s': its bucket is in %s, but no stack of that name is.
                      Create the deployment again: baas --deployment %s admin deployment setup --region %s"""
                .formatted(name, region, name, region));
        }

        config.getAws().setRegion(region);
        Invocation.configService(spec).save(config);

        logger.info("""
            Adopted deployment {} in {}.
              Config: {}
              Bucket: {}
              Table:  {}""",
            name, region, Invocation.configService(spec).fileOf(name), config.bucket(), config.resultsTable());
        return 0;
    }
}
