package pl.wsztajerowski.baas.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import pl.wsztajerowski.baas.LoggingMixin;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.config.ConfigService;
import pl.wsztajerowski.baas.infra.AwsClientFactory;
import pl.wsztajerowski.baas.infra.SsmService;

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
            .append("  coreStackName:            ").append(config.getAws().getCoreStackName()).append('\n')
            .append("  bucket:                   ").append(config.getAws().getBucket()).append('\n')
            .append("  resultsTable:             ").append(config.getAws().getResultsTable() != null
                ? config.getAws().getResultsTable()
                : "<not set> — run: baas config sync --core-stack-name " + config.getAws().getCoreStackName()).append('\n')
            .append("  subnetId:                 ").append(config.getAws().getSubnetId()).append('\n')
            .append("  securityGroupId:          ").append(config.getAws().getSecurityGroupId()).append('\n')
            .append("  vpcId:                    ").append(config.getAws().getVpcId()).append('\n')
            .append("  runnerInstanceProfile:    ").append(config.getAws().getRunnerInstanceProfileName()).append('\n')
            .append("ec2:\n")
            .append("  defaultInstanceType:      ").append(config.getEc2().getDefaultInstanceType()).append('\n')
            .append("  benchmarkTimeoutSeconds:  ").append(config.getEc2().getBenchmarkTimeoutSeconds()).append('\n')
            .append("  wallClockHardKillSeconds: ").append(config.getEc2().getWallClockHardKillSeconds()).append('\n')
            .append("benchmark:\n")
            .append("  asyncProfilerVersion:     ").append(config.getBenchmark().getAsyncProfilerVersion()).append('\n')
            .append("  jarPath:                  ").append(config.getBenchmark().getJarPath()).append('\n')
            .append("mongo:\n")
            .append("  connectionString: ");

        // Show masked mongo URI from SSM
        try {
            RunCommand.operatorCredentialsWarning(config).ifPresent(logger::warn);
            var factory = new AwsClientFactory(
                config.getAws().getRegion(), config.getAws().resolveOperatorProfile());
            try (var ssm = factory.ssm()) {
                var ssmService = new SsmService(ssm);
                var uri = ssmService.getParameterOptional("/" + config.getPrefix() + "/mongo/connection-string");
                dump.append(uri.map(u -> maskMongoUri(u)).orElse("(not set)"));
            }
        } catch (Exception e) {
            dump.append("(unable to retrieve: ").append(e.getMessage()).append(')');
        }

        logger.info("Current configuration:\n{}", dump);
        return 0;
    }

    private String maskMongoUri(String uri) {
        // Replace password between : and @ with ***
        return uri.replaceAll("(mongodb(?:\\+srv)?://[^:]+:)[^@]+(@)", "$1***$2");
    }
}
