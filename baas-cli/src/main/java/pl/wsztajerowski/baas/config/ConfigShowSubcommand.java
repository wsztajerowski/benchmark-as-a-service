package pl.wsztajerowski.baas.config;

import pl.wsztajerowski.baas.cli.Invocation;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import pl.wsztajerowski.baas.cli.LoggingMixin;
import pl.wsztajerowski.baas.session.BaasConfig;
import pl.wsztajerowski.baas.session.ConfigService;
import pl.wsztajerowski.baas.console.Console;

import java.util.concurrent.Callable;

@Command(
    name = "show",
    mixinStandardHelpOptions = true,
    description = "Show current configuration."
)
public class ConfigShowSubcommand implements Callable<Integer> {

    @Mixin LoggingMixin loggingMixin;

    @Spec CommandSpec spec;

    private ConfigService configService() {
        return Invocation.configService(spec);
    }

    @Override
    public Integer call() {
        BaasConfig config = configService().load();
        String dump = render(config, configService().fileOf(config.requirePrefix()));

        // Every value above is local. `config show` makes no AWS call at all now that the masked
        // Mongo connection string — the one field that had to be read from SSM — is gone, so it
        // also has nothing to say about which credentials it would have used.
        //
        // Payload, so the Console: `baas config show > config.txt` has to keep it. It used to be
        // one logger event, which landed on stderr behind a timestamp and left the file empty.
        Console.of(spec.commandLine().getOut()).print(dump);
        return 0;
    }

    /** Split out from {@link #call()} so what it reports is testable without capturing standard output. */
    static String render(BaasConfig config, java.nio.file.Path configFile) {
        // Accumulated and written in one piece, so the columns line up whatever writes it.
        BaasConfig.AwsConfig aws = config.getAws();
        BaasConfig.Ec2Config ec2 = config.getEc2();
        return new StringBuilder()
            .append("Config file: ").append(configFile).append('\n')
            .append("deployment:  ").append(config.getPrefix()).append("  (the file's name)\n")
            .append("aws:\n")
            .append("  deployerProfile:          ").append(orUnset(aws.getDeployerProfile()))
            .append("  (baas admin, used as is)\n")
            // Whatever the source, day-to-day commands run as the operator role: the source only
            // decides whose credentials assume it.
            .append("  operatorProfile:          ")
            .append(aws.getOperatorProfile() != null
                ? aws.getOperatorProfile()
                : aws.getDeployerProfile() != null
                    ? "<not set> — the deployer profile is the source"
                    : "<not set> — the default credential chain is the source")
            .append("  (assumes ").append(deployment(config, c -> c.names().operatorRole())).append(")\n")
            .append("  region:                   ").append(aws.hasRegion()
                ? aws.getRegion()
                : "<not recorded> — found from the bucket on each command").append('\n')
            .append("derived from the name (not stored):\n")
            .append("  stack:                    ").append(deployment(config, BaasConfig::stackName)).append('\n')
            .append("  bucket:                   ").append(deployment(config, BaasConfig::bucket)).append('\n')
            .append("  resultsTable:             ").append(deployment(config, BaasConfig::resultsTable)).append('\n')
            .append("  runnerInstanceProfile:    ").append(deployment(config, BaasConfig::runnerInstanceProfile)).append('\n')
            .append("  amiPointer:               ").append(deployment(config, BaasConfig::amiParameterPath)).append('\n')
            // subnetId and securityGroupId are deliberately absent: they are resolved from the
            // stack on every job, so there is no local value to report and none to go stale.
            .append("ec2:\n")
            .append("  defaultInstanceType:      ").append(ec2.getDefaultInstanceType())
            .append(defaultMark(ec2.isDefaultInstanceTypeSet())).append('\n')
            .append("  benchmarkTimeoutSeconds:  ").append(ec2.getBenchmarkTimeoutSeconds())
            .append(defaultMark(ec2.isBenchmarkTimeoutSecondsSet())).append('\n')
            .append("  watchdogMarginSeconds:    ").append(ec2.getWatchdogMarginSeconds())
            .append(defaultMark(ec2.isWatchdogMarginSecondsSet())).append('\n')
            .append("git:\n")
            .append("  resolveProject:           ").append(config.getGit().isResolveProject())
            .append(defaultMark(config.getGit().isResolveProjectSet())).append('\n')
            .toString();
    }

    /** Defaults are not in the file, so a later release's default reaches this machine. */
    private static String defaultMark(boolean set) {
        return set ? "" : "  (default)";
    }

    private static String orUnset(String value) {
        return value != null ? value : "<not set>";
    }

    /**
     * A derived name, or the reason there isn't one. An unconfigured machine has no deployment
     * to derive from, and {@code config show} is exactly where an operator should learn that.
     */
    private static String deployment(BaasConfig config,
                                       java.util.function.Function<BaasConfig, String> name) {
        try {
            return name.apply(config);
        } catch (IllegalStateException noDeployment) {
            return "<no deployment> — run: baas --deployment baas-<accountId> config sync";
        }
    }
}
