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

    @Option(names = "--instance-type", description = "Default EC2 instance type.")
    String instanceType;

    @Option(names = "--timeout", description = "Benchmark process timeout in seconds.")
    Integer benchmarkTimeout;

    @Option(names = "--watchdog-margin",
        description = "Seconds the self-termination watchdog waits beyond the benchmark timeout "
            + "(minimum " + BaasConfig.MIN_WATCHDOG_MARGIN_SECONDS + ").")
    Integer watchdogMargin;

    // Arity 1, not a bare flag: a flag could turn derivation on but never back off.
    @Option(names = "--git-resolve-project", arity = "1", paramLabel = "<true|false>",
        description = "Derive the project from git when --project is absent: baas run from the "
            + "benchmark JAR's repository, baas results from the current directory's.")
    Boolean gitResolveProject;

    // No --prefix. Adopting an installation is `baas config sync --name`, which checks the stack
    // exists first; this option wrote the same field unchecked, so a typo surfaced only as the
    // first real command's AWS error. It dated from when the prefix was a name you chose.
    //
    // No --region, for the same reason. The region is the installation's: `baas admin setup
    // --region` chooses it and `config sync` finds it from the bucket. Set by hand, it aimed a
    // machine at a region with no installation, and `run` then advised building an image there.

    @Spec CommandSpec spec;

    private ConfigService configService() {
        return BaasApp.configService(spec);
    }

    @Override
    public Integer call() {
        if (watchdogMargin != null && watchdogMargin < BaasConfig.MIN_WATCHDOG_MARGIN_SECONDS) {
            logger.error("--watchdog-margin must be at least {} seconds; got {}.",
                BaasConfig.MIN_WATCHDOG_MARGIN_SECONDS, watchdogMargin);
            return 2;
        }
        BaasConfig config = configService().loadOrEmpty();

        if (awsProfile != null) config.getAws().setProfile(awsProfile);
        if (operatorProfile != null) config.getAws().setOperatorProfile(operatorProfile);
        if (instanceType != null) config.getEc2().setDefaultInstanceType(instanceType);
        if (benchmarkTimeout != null) config.getEc2().setBenchmarkTimeoutSeconds(benchmarkTimeout);
        if (watchdogMargin != null) config.getEc2().setWatchdogMarginSeconds(watchdogMargin);
        if (gitResolveProject != null) config.getGit().setResolveProject(gitResolveProject);

        configService().save(config);
        logger.info("Configuration saved to {}", configService().configFilePath());
        return 0;
    }
}
