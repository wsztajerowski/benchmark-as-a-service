package pl.wsztajerowski.baas.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import pl.wsztajerowski.baas.LoggingMixin;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.config.ConfigService;

import java.util.concurrent.Callable;

@Command(
    name = "show",
    mixinStandardHelpOptions = true,
    description = "Show current configuration."
)
public class ConfigShowSubcommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(ConfigShowSubcommand.class);

    @Mixin LoggingMixin loggingMixin;

    private final ConfigService configService = new ConfigService();

    @Override
    public Integer call() {
        BaasConfig config = configService.load();

        // Accumulated and logged as one event rather than a line at a time: SimpleLogger prefixes
        // every call with a timestamp, which would break the column alignment this dump relies on.
        var dump = new StringBuilder()
            .append("Config file: ").append(configService.configFilePath()).append('\n')
            .append("prefix:      ").append(config.getPrefix()).append('\n')
            .append("aws:\n")
            .append("  profile:                  ").append(config.getAws().getProfile())
            .append("  (admin setup/teardown)\n")
            // Unset here is not cosmetic — it means run/results/config fall through to the default
            // credential chain instead of assuming the operator role, so say what to do about it.
            .append("  operatorProfile:          ")
            .append(config.getAws().getOperatorProfile() != null
                ? config.getAws().getOperatorProfile() + "  (run/results/config)"
                : "<not set> — run: baas config set --operator-profile <name>").append('\n')
            .append("  region:                   ").append(config.getAws().getRegion()).append('\n')
            .append("derived from prefix (not stored):\n")
            .append("  stack:                    ").append(installation(config, BaasConfig::stackName)).append('\n')
            .append("  bucket:                   ").append(installation(config, BaasConfig::bucket)).append('\n')
            .append("  resultsTable:             ").append(installation(config, BaasConfig::resultsTable)).append('\n')
            .append("  runnerInstanceProfile:    ").append(installation(config, BaasConfig::runnerInstanceProfile)).append('\n')
            .append("  amiPointer:               ").append(installation(config, BaasConfig::amiParameterPath)).append('\n')
            // subnetId and securityGroupId are deliberately absent: they are resolved from the
            // stack on every run, so there is no local value to report and none to go stale.
            .append("ec2:\n")
            .append("  defaultInstanceType:      ").append(config.getEc2().getDefaultInstanceType()).append('\n')
            .append("  benchmarkTimeoutSeconds:  ").append(config.getEc2().getBenchmarkTimeoutSeconds()).append('\n')
            .append("  wallClockHardKillSeconds: ").append(config.getEc2().getWallClockHardKillSeconds()).append('\n')
            .append("runner:\n")
            .append("  sourceRepo:               ").append(config.getRunner().getSourceRepo()).append('\n');

        // Every value above is local. `config show` makes no AWS call at all now that the masked
        // Mongo connection string — the one field that had to be read from SSM — is gone, so it
        // also has nothing to say about which credentials it would have used.
        logger.info("Current configuration:\n{}", dump);
        return 0;
    }

    /**
     * A derived name, or the reason there isn't one. An unconfigured machine has no installation
     * to derive from, and {@code config show} is exactly where an operator should learn that.
     */
    private static String installation(BaasConfig config,
                                       java.util.function.Function<BaasConfig, String> name) {
        try {
            return name.apply(config);
        } catch (IllegalStateException noInstallation) {
            return "<no installation> — run: baas config sync --name baas-<accountId>";
        }
    }
}
