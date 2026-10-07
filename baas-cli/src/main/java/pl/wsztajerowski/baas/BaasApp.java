package pl.wsztajerowski.baas;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;
import picocli.CommandLine.ParseResult;
import pl.wsztajerowski.baas.commands.ConfigCommand;
import pl.wsztajerowski.baas.commands.ResultsCommand;
import pl.wsztajerowski.baas.commands.ResultsQuerySubcommand;
import pl.wsztajerowski.baas.commands.RunCommand;
import pl.wsztajerowski.baas.commands.JobsCommand;
import pl.wsztajerowski.baas.commands.admin.AdminCommand;
import pl.wsztajerowski.baas.config.ConfigService;

import java.nio.file.Path;

@Command(
    name = "baas",
    mixinStandardHelpOptions = true,
    versionProvider = BaasApp.VersionProvider.class,
    description = "Benchmark as a Service CLI — provision AWS infrastructure and run benchmarks.",
    subcommands = {
        AdminCommand.class,
        ConfigCommand.class,
        JobsCommand.class,
        ResultsCommand.class,
        // The two aliases: each a verb owned by exactly one noun, registered a second time here.
        RunCommand.class,
        ResultsQuerySubcommand.class
    },
    // Lines stay under 80 columns: the footer wraps at the usage width and re-wrapping
    // mid-command is unreadable. The command list itself is the grouped map rendered by
    // commandMap(), not picocli's flat list.
    footer = {
        "",
        "First run, in order:",
        "  baas admin deployment setup            # deploy; prints the IAM policy",
        "                                         # your identity lacks, if any",
        "  baas admin image build                 # bake the runner AMI (~15 min)",
        "  baas config set --operator-profile <p> # day-to-day credentials",
        "  baas run --benchmark-jar target/b.jar --project my-bench \\",
        "    jmh -- MyBenchmark -f 1              # note the -- separator"
    }
)
public class BaasApp implements Runnable {

    /**
     * The version cannot be a constant {@code version = ...} attribute: it is read from the JAR
     * manifest at run time, and a reactor build legitimately has none. Reporting the placeholder
     * unchanged is the point — {@code baas run} refuses to launch on it, so a user asking why
     * needs {@code -V} to say which build they are holding.
     */
    public static class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[]{"baas " + BaasVersion.current()};
        }
    }

    @Mixin LoggingMixin loggingMixin;

    @Spec CommandSpec spec;

    /**
     * Inherited, so it parses on either side of any subcommand — and picocli writes every copy back
     * to this one field, which is why commands read it from the root rather than declaring their own.
     * It replaced the per-command {@code --results-table}/{@code --bucket} overrides: addressing
     * another deployment means naming its configuration, not one of its resources.
     */
    /**
     * Every pointer to a concrete deployment is this option — it replaced {@code teardown
     * --deployment} and {@code config sync --deployment}. Never positional. While a machine holds one
     * deployment's configuration it may only name that one, so a typo cannot aim a command at a
     * different deployment's resources; naming several is the multiple-deployments change's.
     */
    @Option(names = "--deployment", scope = ScopeType.INHERIT, paramLabel = "<name>",
        description = "Deployment to act on (default: the one this machine is configured for).")
    String deployment;

    /** The {@code --deployment} the command line named, if any. */
    public static java.util.Optional<String> deployment(CommandSpec spec) {
        if (spec != null && spec.root().userObject() instanceof BaasApp app && app.deployment != null
            && !app.deployment.isBlank()) {
            return java.util.Optional.of(app.deployment.strip());
        }
        return java.util.Optional.empty();
    }

    @Option(names = "--config-path", scope = ScopeType.INHERIT, paramLabel = "<file>",
        description = "Configuration file to use instead of ~/.baas/config.yaml.")
    Path configPath;

    /**
     * The configuration a command should use. Resolved at call time, never in a field initialiser:
     * fields are initialised while picocli builds the command tree, before {@code --config-path} is
     * parsed. A command constructed outside a {@code BaasApp} tree (a unit test) gets the default.
     */
    public static ConfigService configService(CommandSpec spec) {
        if (spec != null && spec.root().userObject() instanceof BaasApp app) {
            return ConfigService.at(app.configPath);
        }
        return new ConfigService();
    }

    /**
     * The root command line with the grouped command map in place of picocli's flat list. Built
     * here so that {@code main} and the tests render the same help.
     */
    public static CommandLine commandLine(BaasApp app) {
        var commandLine = new CommandLine(app);
        commandLine.getHelpSectionMap().put(CommandLine.Model.UsageMessageSpec.SECTION_KEY_COMMAND_LIST_HEADING,
            help -> System.lineSeparator());
        commandLine.getHelpSectionMap().put(CommandLine.Model.UsageMessageSpec.SECTION_KEY_COMMAND_LIST,
            help -> commandMap(commandLine));
        return commandLine;
    }

    /**
     * {@code baas --help}'s command list: the shortcuts with their targets, then the operator nouns
     * and the deployer nouns with their verbs. Read from the command tree, so it cannot drift from
     * it. Headings name the role, never a configuration key.
     */
    static String commandMap(CommandLine root) {
        var sub = root.getSubcommands();
        var out = new StringBuilder();
        out.append("Shortcuts%n".formatted());
        out.append("  %-23s %s%n".formatted("run = jobs run", firstLine(sub.get("run"))));
        out.append("  %-23s %s%n".formatted("query = results query", firstLine(sub.get("query"))));
        out.append("%nOperator commands (operator AWS credentials)%n".formatted());
        for (String noun : java.util.List.of("jobs", "results", "config")) {
            out.append("  %-23s %s%n".formatted(noun, verbs(sub.get(noun))));
        }
        out.append("%nDeployer commands (deployer AWS credentials)%n".formatted());
        var admin = sub.get("admin").getSubcommands();
        for (String noun : java.util.List.of("deployment", "image")) {
            out.append("  %-23s %s%n".formatted("admin " + noun, verbs(admin.get(noun))));
        }
        return out.toString();
    }

    private static String verbs(CommandLine noun) {
        return String.join(", ", noun.getSubcommands().keySet());
    }

    private static String firstLine(CommandLine command) {
        String[] description = command.getCommandSpec().usageMessage().description();
        return description.length == 0 ? "" : description[0];
    }

    public static void main(String[] args) {
        // Must happen before the CommandLine is built — see LoggingMixin#applyEarlyVerbosity.
        LoggingMixin.applyEarlyVerbosity(args);
        BaasApp app = new BaasApp();
        System.exit(commandLine(app)
            .setExecutionStrategy(app::executionStrategy)
            .setExecutionExceptionHandler(BaasApp::reportFailure)
            .execute(args));
    }

    private int executionStrategy(ParseResult parseResult) {
        if (loggingMixin.verbose) {
            System.setProperty(LoggingMixin.LEVEL_PROPERTY, "debug");
        }
        String refusal = deploymentRefusal(parseResult);
        if (refusal != null) {
            LoggerFactory.getLogger(BaasApp.class).error("{}", refusal);
            return 2;
        }
        return new CommandLine.RunLast().execute(parseResult); // default execution strategy
    }

    /**
     * Why {@code --deployment} cannot be honoured, or {@code null}. Checked once, here, before any
     * command runs, so no command can forget it. Two commands check it themselves: {@code config
     * sync}, whose job is to adopt the named deployment, and {@code admin deployment setup}, which
     * compares it with the name it derives from the account.
     */
    String deploymentRefusal(ParseResult parseResult) {
        if (deployment == null || deployment.isBlank()) {
            return null;
        }
        ParseResult leaf = parseResult;
        while (leaf.hasSubcommand()) {
            leaf = leaf.subcommand();
        }
        Object command = leaf.commandSpec().userObject();
        if (command instanceof pl.wsztajerowski.baas.commands.ConfigSyncSubcommand
            || command instanceof pl.wsztajerowski.baas.commands.admin.SetupCommand) {
            return null;
        }
        String configured = ConfigService.at(configPath).loadOrEmpty().getPrefix();
        if (configured == null || configured.isBlank()) {
            return "No deployment is configured on this machine, so --deployment " + deployment.strip()
                + " cannot be reached. Adopt it with: baas config sync --deployment " + deployment.strip();
        }
        if (!configured.equals(deployment.strip())) {
            return "--deployment " + deployment.strip() + " is not the deployment this machine is "
                + "configured for (" + configured + "). Nothing was done.";
        }
        return null;
    }

    /**
     * A stack trace names SDK internals, not anything the user can act on. Log the message
     * — which for stack failures carries the CloudFormation reason — and keep the trace at debug,
     * behind {@code -v}, for when the message genuinely is not enough.
     *
     * <p>The logger is looked up here, not held in a {@code static final} field: {@code BaasApp}
     * is initialised before {@code main} runs {@link LoggingMixin#applyEarlyVerbosity}, and
     * SimpleLogger pins a logger's level at construction, so a static one would never see
     * {@code -v} and the trace would be unreachable.
     */
    private static int reportFailure(Exception ex, CommandLine commandLine,
                                     CommandLine.ParseResult parseResult) {
        Logger logger = LoggerFactory.getLogger(BaasApp.class);
        String message = ex.getMessage() != null ? ex.getMessage() : ex.toString();
        logger.error("{}", message);
        if (logger.isDebugEnabled()) {
            logger.debug("Stack trace:", ex);
        } else {
            logger.info("(run with -v for the full stack trace)");
        }
        return commandLine.getCommandSpec().exitCodeOnExecutionException();
    }

    @Override
    public void run() {
        // Help text is program output, not a log event — picocli renders and wraps it itself, onto
        // the same writer every other payload uses so the two cannot interleave out of order.
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
