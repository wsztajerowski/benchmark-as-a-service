package pl.wsztajerowski.baas.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.config.ConfigService;
import pl.wsztajerowski.baas.infra.AwsClientFactory;
import pl.wsztajerowski.baas.infra.SsmService;

import java.util.concurrent.Callable;

@Command(
    name = "set",
    mixinStandardHelpOptions = true,
    description = "Update configuration values."
)
public class ConfigSetSubcommand implements Callable<Integer> {

    @Option(names = "--mongo-uri", description = "MongoDB connection string (stored in SSM SecureString).")
    String mongoUri;

    @Option(names = "--aws-profile", description = "AWS CLI profile name.")
    String awsProfile;

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
        if (region != null) config.getAws().setRegion(region);
        if (bucket != null) config.getAws().setBucket(bucket);
        if (stackName != null) config.getAws().setCoreStackName(stackName);
        if (instanceType != null) config.getEc2().setDefaultInstanceType(instanceType);
        if (benchmarkTimeout != null) config.getEc2().setBenchmarkTimeoutSeconds(benchmarkTimeout);
        if (wallClock != null) config.getEc2().setWallClockHardKillSeconds(wallClock);

        if (mongoUri != null) {
            validateMongoUri(mongoUri);
            var factory = new AwsClientFactory(config.getAws().getRegion(), config.getAws().getProfile());
            try (var ssm = factory.ssm()) {
                new SsmService(ssm).putSecureParameter(
                    "/" + config.getPrefix() + "/mongo/connection-string", mongoUri);
                System.out.println("MongoDB URI stored in SSM: /" + config.getPrefix() + "/mongo/connection-string");
            }
        }

        configService.save(config);
        System.out.println("Configuration saved to " + configService.configFilePath());
        return 0;
    }

    private void validateMongoUri(String uri) {
        var cs = new com.mongodb.ConnectionString(uri);
        if (cs.getDatabase() == null || cs.getDatabase().isEmpty()) {
            throw new IllegalArgumentException(
                "MongoDB URI must include a database name (e.g. mongodb+srv://user:pass@host/mydb)");
        }
    }
}
