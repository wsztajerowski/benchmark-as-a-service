package pl.wsztajerowski.baas.commands;

import picocli.CommandLine.Command;
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

    private final ConfigService configService = new ConfigService();

    @Override
    public Integer call() {
        BaasConfig config = configService.load();
        System.out.println("Config file: " + configService.configFilePath());
        System.out.println("prefix:      " + config.getPrefix());
        System.out.println("aws:");
        System.out.println("  profile:                  " + config.getAws().getProfile());
        System.out.println("  region:                   " + config.getAws().getRegion());
        System.out.println("  stackName:                " + config.getAws().getStackName());
        System.out.println("  bucket:                   " + config.getAws().getBucket());
        System.out.println("  subnetId:                 " + config.getAws().getSubnetId());
        System.out.println("  securityGroupId:          " + config.getAws().getSecurityGroupId());
        System.out.println("  vpcId:                    " + config.getAws().getVpcId());
        System.out.println("  runnerInstanceProfile:    " + config.getAws().getRunnerInstanceProfileName());
        System.out.println("ec2:");
        System.out.println("  defaultInstanceType:      " + config.getEc2().getDefaultInstanceType());
        System.out.println("  benchmarkTimeoutSeconds:  " + config.getEc2().getBenchmarkTimeoutSeconds());
        System.out.println("  wallClockHardKillSeconds: " + config.getEc2().getWallClockHardKillSeconds());
        System.out.println("benchmark:");
        System.out.println("  asyncProfilerVersion:     " + config.getBenchmark().getAsyncProfilerVersion());
        System.out.println("  jarPath:                  " + config.getBenchmark().getJarPath());

        // Show masked mongo URI from SSM
        try {
            var factory = new AwsClientFactory(config.getAws().getRegion(), config.getAws().getProfile());
            try (var ssm = factory.ssm()) {
                var ssmService = new SsmService(ssm);
                var uri = ssmService.getParameterOptional("/" + config.getPrefix() + "/mongo/connection-string");
                System.out.println("mongo:");
                System.out.println("  connectionString: " + uri.map(u -> maskMongoUri(u)).orElse("(not set)"));
            }
        } catch (Exception e) {
            System.out.println("mongo:");
            System.out.println("  connectionString: (unable to retrieve: " + e.getMessage() + ")");
        }
        return 0;
    }

    private String maskMongoUri(String uri) {
        // Replace password between : and @ with ***
        return uri.replaceAll("(mongodb(?:\\+srv)?://[^:]+:)[^@]+(@)", "$1***$2");
    }
}
