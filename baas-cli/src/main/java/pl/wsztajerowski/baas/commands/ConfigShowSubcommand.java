package pl.wsztajerowski.baas.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;
import pl.wsztajerowski.baas.BaasApp;
import pl.wsztajerowski.baas.LoggingMixin;
import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.config.ConfigService;
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
        return BaasApp.configService(spec);
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
        return new StringBuilder()
            .append("Config file: ").append(configFile).append('\n')
            .append("prefix:      ").append(config.getPrefix()).append('\n')
            .append("aws:\n")
            .append("  deployerProfile:          ").append(config.getAws().getDeployerProfile())
            .append("  (baas admin)\n")
            // Unset here is not cosmetic — it means run/results/config fall through to the default
            // credential chain instead of assuming the operator role, so say what to do about it.
            .append("  operatorProfile:          ")
            .append(config.getAws().getOperatorProfile() != null
                ? config.getAws().getOperatorProfile() + "  (run/results/config)"
                : "<not set> — run: baas config set --operator-aws-profile <name>").append('\n')
            .append("  region:                   ").append(config.getAws().resolveRegion())
            .append(config.getAws().getRegion() != null ? "" : "  (not in the file: AWS_REGION, else the default)")
            .append('\n')
            .append("derived from prefix (not stored):\n")
            .append("  stack:                    ").append(deployment(config, BaasConfig::stackName)).append('\n')
            .append("  bucket:                   ").append(deployment(config, BaasConfig::bucket)).append('\n')
            .append("  resultsTable:             ").append(deployment(config, BaasConfig::resultsTable)).append('\n')
            .append("  runnerInstanceProfile:    ").append(deployment(config, BaasConfig::runnerInstanceProfile)).append('\n')
            .append("  amiPointer:               ").append(deployment(config, BaasConfig::amiParameterPath)).append('\n')
            // subnetId and securityGroupId are deliberately absent: they are resolved from the
            // stack on every job, so there is no local value to report and none to go stale.
            .append("ec2:\n")
            .append("  defaultInstanceType:      ").append(config.getEc2().getDefaultInstanceType()).append('\n')
            .append("  benchmarkTimeoutSeconds:  ").append(config.getEc2().getBenchmarkTimeoutSeconds()).append('\n')
            .append("  watchdogMarginSeconds:    ").append(config.getEc2().getWatchdogMarginSeconds()).append('\n')
            .append("git:\n")
            .append("  resolveProject:           ").append(config.getGit().isResolveProject()).append('\n')
            .append("runner:\n")
            .append("  sourceRepo:               ").append(config.getRunner().getSourceRepo()).append('\n')
            .toString();
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
            return "<no deployment> — run: baas config sync --deployment baas-<accountId>";
        }
    }
}
