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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

@Command(
    name = "sync",
    mixinStandardHelpOptions = true,
    description = "Populate config.yaml from an existing core stack's outputs (operator credentials)."
)
public class ConfigSyncSubcommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(ConfigSyncSubcommand.class);

    @Mixin LoggingMixin loggingMixin;

    @Option(names = "--core-stack-name", required = true,
        description = "Core stack to read, as printed by `baas admin setup` (e.g. baas-a1b2c3d4).")
    String coreStackName;

    private final ConfigService configService = new ConfigService();

    @Override
    public Integer call() {
        BaasConfig config = configService.load();
        RunCommand.operatorCredentialsWarning(config).ifPresent(logger::warn);

        var factory = new AwsClientFactory(
            config.getAws().getRegion(), config.getAws().resolveOperatorProfile());

        try (var cf = factory.cloudFormation()) {
            var outputs = new CloudFormationService(cf).getStackOutputs(coreStackName);
            if (outputs.isEmpty()) {
                logger.error("Stack '{}' has no outputs, or does not exist in {}.",
                    coreStackName, config.getAws().getRegion());
                return 1;
            }
            var aws = config.getAws();
            aws.setCoreStackName(coreStackName);

            // Only overwrite what the stack actually reports. Defaulting a missing output to
            // "" would silently blank a value the operator already had working.
            List<String> missing = new ArrayList<>();
            applyIfPresent(outputs, "BucketName", aws::setBucket, missing);
            applyIfPresent(outputs, "SubnetId", aws::setSubnetId, missing);
            applyIfPresent(outputs, "SecurityGroupId", aws::setSecurityGroupId, missing);
            applyIfPresent(outputs, "VpcId", aws::setVpcId, missing);
            applyIfPresent(outputs, "RunnerInstanceProfileName", aws::setRunnerInstanceProfileName, missing);
            applyIfPresent(outputs, "ResultsTableName", aws::setResultsTable, missing);

            if (!missing.isEmpty()) {
                logger.warn("Stack '{}' did not report {} — existing local values were left unchanged.",
                    coreStackName, String.join(", ", missing));
            }
        }

        // SSM paths (the runner AMI pointer) are keyed by the prefix, which is the stack name
        // minus its "baas-" namespace.
        config.setPrefix(coreStackName.startsWith("baas-") ? coreStackName.substring(5) : coreStackName);

        configService.save(config);
        logger.info("Configuration synced from {} to {}", coreStackName, configService.configFilePath());
        return 0;
    }

    private static void applyIfPresent(Map<String, String> outputs, String key,
                                       Consumer<String> setter, List<String> missing) {
        String value = outputs.get(key);
        if (value == null || value.isBlank()) {
            missing.add(key);
        } else {
            setter.accept(value);
        }
    }
}
