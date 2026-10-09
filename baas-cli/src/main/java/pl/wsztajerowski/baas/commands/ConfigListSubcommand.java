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
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.console.Table;
import pl.wsztajerowski.baas.console.Table.Column;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * The deployments this machine is configured for, read from their files alone: no AWS call, no
 * credentials, and no deployment selected, so it answers however many are configured — which is
 * exactly when the others refuse to guess. {@code list} is a shared verb, so it has no top-level
 * alias.
 */
@Command(
    name = "list",
    mixinStandardHelpOptions = true,
    description = "List the deployments configured on this machine (no AWS call)."
)
public class ConfigListSubcommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(ConfigListSubcommand.class);

    static final List<String> FORMATS = List.of("table", "json");

    private static final List<Column> COLUMNS = List.of(
        Column.left("DEPLOYMENT", 32),
        Column.left("REGION", 14),
        Column.left("OPERATOR PROFILE", 28),
        Column.left("DEPLOYER PROFILE", 28));

    @Mixin LoggingMixin loggingMixin;

    @Spec CommandSpec spec;

    /** Set by tests; otherwise built from picocli's {@code getOut()} on first use. */
    Console console;

    @Option(names = "--format", defaultValue = "table", description = "Output format: table (default), json.")
    String format;

    @Override
    public Integer call() {
        if (!FORMATS.contains(format.toLowerCase(Locale.ROOT))) {
            logger.error("Unknown --format '{}'. Valid: {}.", format, String.join(", ", FORMATS));
            return 2;
        }
        List<BaasConfig> deployments = BaasApp.configService(spec).all();
        if (format.equalsIgnoreCase("json")) {
            printJson(deployments);
        } else {
            printTable(deployments);
        }
        return 0;
    }

    /** Payload, including the empty answer: a redirect of stdout should keep it. */
    private void printTable(List<BaasConfig> deployments) {
        var out = console();
        if (deployments.isEmpty()) {
            out.println("No deployment is configured on this machine.");
            return;
        }
        var table = new Table(out, 105, COLUMNS);
        table.printHeader();
        for (BaasConfig deployment : deployments) {
            var aws = deployment.getAws();
            table.printRow(deployment.getPrefix(), orDash(aws.getRegion()),
                orDash(aws.getOperatorProfile()), orDash(aws.getDeployerProfile()));
        }
    }

    private void printJson(List<BaasConfig> deployments) {
        var out = console();
        out.println("[");
        for (int i = 0; i < deployments.size(); i++) {
            var deployment = deployments.get(i);
            var aws = deployment.getAws();
            out.printf("  {\"deployment\":%s,\"region\":%s,\"operatorProfile\":%s,\"deployerProfile\":%s}%s%n",
                json(deployment.getPrefix()), json(aws.getRegion()), json(aws.getOperatorProfile()),
                json(aws.getDeployerProfile()), i < deployments.size() - 1 ? "," : "");
        }
        out.println("]");
    }

    private static String json(String value) {
        return value == null ? "null" : ResultsQuerySubcommand.jsonString(value);
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private Console console() {
        if (console == null) {
            console = Console.of(spec.commandLine().getOut());
        }
        return console;
    }
}
