package pl.wsztajerowski.baas.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.config.ConfigService;
import pl.wsztajerowski.baas.infra.AwsClientFactory;
import pl.wsztajerowski.baas.infra.CloudFormationService;

import java.util.concurrent.Callable;

@Command(
    name = "sync",
    mixinStandardHelpOptions = true,
    description = "Populate config.yaml from an existing core stack's outputs (operator credentials)."
)
public class ConfigSyncSubcommand implements Callable<Integer> {

    @Option(names = "--core-stack-name", required = true,
        description = "Core stack to read, as printed by `baas admin setup` (e.g. baas-a1b2c3d4).")
    String coreStackName;

    private final ConfigService configService = new ConfigService();

    @Override
    public Integer call() {
        BaasConfig config = configService.load();
        RunCommand.operatorCredentialsWarning(config).ifPresent(System.err::println);

        var factory = new AwsClientFactory(
            config.getAws().getRegion(), config.getAws().resolveOperatorProfile());

        try (var cf = factory.cloudFormation()) {
            var outputs = new CloudFormationService(cf).getStackOutputs(coreStackName);
            if (outputs.isEmpty()) {
                System.err.println("Stack '" + coreStackName + "' has no outputs, or does not exist in "
                    + config.getAws().getRegion() + ".");
                return 1;
            }
            config.getAws().setCoreStackName(coreStackName);
            config.getAws().setBucket(outputs.getOrDefault("BucketName", ""));
            config.getAws().setSubnetId(outputs.getOrDefault("SubnetId", ""));
            config.getAws().setSecurityGroupId(outputs.getOrDefault("SecurityGroupId", ""));
            config.getAws().setVpcId(outputs.getOrDefault("VpcId", ""));
            config.getAws().setRunnerInstanceProfileName(outputs.getOrDefault("RunnerInstanceProfileName", ""));
        }

        // The SSM mongo path is keyed by the prefix, which is the stack name minus its "baas-" namespace.
        config.setPrefix(coreStackName.startsWith("baas-") ? coreStackName.substring(5) : coreStackName);

        configService.save(config);
        System.out.println("Configuration synced from " + coreStackName + " to " + configService.configFilePath());
        return 0;
    }
}
