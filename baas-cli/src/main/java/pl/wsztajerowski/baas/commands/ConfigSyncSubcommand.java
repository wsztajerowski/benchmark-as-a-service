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
import pl.wsztajerowski.baas.infra.S3UploadService;

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

    @Spec CommandSpec spec;

    private ConfigService configService() {
        return BaasApp.configService(spec);
    }

    /**
     * The region the installation's bucket lives in, empty when there is no such bucket. The bucket
     * is named by the prefix and bucket names are global, so this answers from a client in any
     * region. Overridden by tests, which have no bucket.
     */
    java.util.Optional<String> bucketRegion(BaasConfig config) {
        var factory = new AwsClientFactory(
            config.getAws().resolveRegion(), config.getAws().resolveOperatorProfile());
        try (var s3 = factory.s3()) {
            return new S3UploadService(s3).bucketRegion(name);
        }
    }

    /** The named stack's outputs in that region, empty when it does not exist. Overridden by tests. */
    java.util.Map<String, String> stackOutputs(BaasConfig config, String region) {
        var factory = new AwsClientFactory(region, config.getAws().resolveOperatorProfile());
        try (var cf = factory.cloudFormation()) {
            return new CloudFormationService(cf).getStackOutputs(name);
        }
    }

    @Override
    public Integer call() {
        BaasConfig config = configService().loadOrEmpty();
        RunCommand.operatorCredentialsWarning(config).ifPresent(logger::warn);

        // The region is the installation's, chosen once by `baas admin setup`, so it is found
        // rather than asked for: the bucket carries the prefix's name, names are global, and S3
        // says where it lives. A machine re-pointed at a rebuilt installation is re-adopted by this
        // same command, and CI follows the installation rather than whatever AWS_REGION it set.
        var region = bucketRegion(config);
        if (region.isEmpty()) {
            logger.error("""
                    No installation named '{}' in this account: there is no bucket of that name.
                      The name is the one `baas admin setup` printed, e.g. baas-123456789012.
                      Or create one: baas admin setup""", name);
            return 1;
        }

        // The stack is read to prove the installation exists, not to harvest values from it.
        // Everything the CLI needs is either derived from the prefix or resolved from this same
        // stack at the moment it is used, so copying outputs into the file would only create a
        // second, staler source of truth.
        var outputs = stackOutputs(config, region.get());
        if (outputs.isEmpty()) {
            logger.error("""
                    No installation named '{}': its bucket is in {}, but no stack of that name is.
                      A teardown retains the bucket. Create the installation again: \
                baas admin setup --region {}""",
                name, region.get(), region.get());
            return 1;
        }
        if (!outputs.containsKey("ResultsTableName")) {
            logger.warn("Stack '{}' reports no ResultsTableName output — it may predate this "
                + "version of the core template. `baas results` will fail until it is updated.", name);
        }

        config.setPrefix(name);
        config.getAws().setRegion(region.get());
        configService().save(config);

        logger.info("""
            Adopted installation {} in {}.
              Config: {}
              Bucket: {}
              Table:  {}""",
            name, region.get(), configService().configFilePath(), config.bucket(), config.resultsTable());
        return 0;
    }
}
