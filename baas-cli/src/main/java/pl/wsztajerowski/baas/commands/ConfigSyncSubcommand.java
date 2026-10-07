package pl.wsztajerowski.baas.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
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
    description = "Adopt an existing deployment on this machine."
)
public class ConfigSyncSubcommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(ConfigSyncSubcommand.class);

    @Mixin LoggingMixin loggingMixin;

    /**
     * The deployment being adopted: the global {@code --deployment}, or the only one configured.
     * Never derived from the caller's account, although it could be.
     *
     * <p>A bare {@code baas config sync} on a machine with no local state would adopt whatever
     * deployment the currently active credentials imply. In CI that is the worst place for an
     * implicit choice: a workflow federating into an unexpected role, or a leftover
     * {@code AWS_PROFILE}, would bind the machine to another account's deployment and fail later,
     * after provisioning, somewhere unrelated. So with nothing configured the name is required;
     * with exactly one configured, a bare sync re-syncs that one.
     */
    String name;

    @Spec CommandSpec spec;

    private ConfigService configService() {
        return BaasApp.configService(spec);
    }

    /**
     * The region the deployment's bucket lives in, empty when there is no such bucket. The bucket
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
        BaasConfig config;
        try {
            config = configService().loadForSync();
        } catch (IllegalStateException e) {
            logger.error(e.getMessage());
            return 2;
        }
        name = config.requirePrefix();
        RunCommand.operatorCredentialsWarning(config).ifPresent(logger::warn);

        // The region is the deployment's, chosen once by `baas admin deployment setup`, so it is found
        // rather than asked for: the bucket carries the prefix's name, names are global, and S3
        // says where it lives. A machine re-pointed at a rebuilt deployment is re-adopted by this
        // same command, and CI follows the deployment rather than whatever AWS_REGION it set.
        var region = bucketRegion(config);
        if (region.isEmpty()) {
            logger.error("""
                    No deployment named '{}' in this account: there is no bucket of that name.
                      The name is the one `baas admin deployment setup` printed, e.g. baas-123456789012.
                      Or create one: baas admin deployment setup""", name);
            return 1;
        }

        // The stack is read to prove the deployment exists, not to harvest values from it.
        // Everything the CLI needs is either derived from the prefix or resolved from this same
        // stack at the moment it is used, so copying outputs into the file would only create a
        // second, staler source of truth.
        var outputs = stackOutputs(config, region.get());
        if (outputs.isEmpty()) {
            logger.error("""
                    No deployment named '{}': its bucket is in {}, but no stack of that name is.
                      A teardown retains the bucket. Create the deployment again: \
                baas admin deployment setup --region {}""",
                name, region.get(), region.get());
            return 1;
        }
        if (!outputs.containsKey("ResultsTableName")) {
            logger.warn("Stack '{}' reports no ResultsTableName output — it may predate this "
                + "version of the core template. `baas results query` will fail until it is updated.", name);
        }

        config.setPrefix(name);
        config.getAws().setRegion(region.get());
        configService().save(config);

        logger.info("""
            Adopted deployment {} in {}.
              Config: {}
              Bucket: {}
              Table:  {}""",
            name, region.get(), configService().fileOf(name), config.bucket(), config.resultsTable());
        return 0;
    }
}
