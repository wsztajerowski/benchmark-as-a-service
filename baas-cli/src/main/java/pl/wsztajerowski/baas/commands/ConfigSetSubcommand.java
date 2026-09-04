package pl.wsztajerowski.baas.commands;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import pl.wsztajerowski.baas.LoggingMixin;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.config.ConfigService;

import java.util.concurrent.Callable;

@Command(
    name = "set",
    mixinStandardHelpOptions = true,
    description = "Update configuration values."
)
public class ConfigSetSubcommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(ConfigSetSubcommand.class);

    @Mixin LoggingMixin loggingMixin;

    @Option(names = "--aws-profile", description = "AWS CLI profile name.")
    String awsProfile;

    @Option(names = "--operator-profile",
        description = "AWS CLI profile that assumes BaasCliOperatorRole — used by run/results/config.")
    String operatorProfile;

    @Option(names = "--region", description = "AWS region.")
    String region;

    @Option(names = "--bucket", description = "S3 bucket name.")
    String bucket;

    @Option(names = "--instance-type", description = "Default EC2 instance type.")
    String instanceType;

    @Option(names = "--timeout", description = "Benchmark process timeout in seconds.")
    Integer benchmarkTimeout;

    @Option(names = "--max-wall-clock", description = "Absolute wall-clock cap in seconds.")
    Integer wallClock;

    @Option(names = "--prefix", description = "Resource name prefix.")
    String prefix;

    @Option(names = "--stack-name", description = "CloudFormation stack name.")
    String stackName;

    private final ConfigService configService = new ConfigService();

    @Override
    public Integer call() {
        BaasConfig config = configService.load();

        if (prefix != null) config.setPrefix(prefix);
        if (awsProfile != null) config.getAws().setProfile(awsProfile);
        if (operatorProfile != null) config.getAws().setOperatorProfile(operatorProfile);
        if (region != null) config.getAws().setRegion(region);
        if (bucket != null) config.getAws().setBucket(bucket);
        if (stackName != null) config.getAws().setCoreStackName(stackName);
        if (instanceType != null) config.getEc2().setDefaultInstanceType(instanceType);
        if (benchmarkTimeout != null) config.getEc2().setBenchmarkTimeoutSeconds(benchmarkTimeout);
        if (wallClock != null) config.getEc2().setWallClockHardKillSeconds(wallClock);

        configService.save(config);
        logger.info("Configuration saved to {}", configService.configFilePath());
        return 0;
    }
}
