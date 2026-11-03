package pl.wsztajerowski.baas.deployment;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import pl.wsztajerowski.baas.aws.CloudFormationService;
import pl.wsztajerowski.baas.cli.CommandContext;
import pl.wsztajerowski.baas.cli.CommandRefusal;
import pl.wsztajerowski.baas.cli.Invocation;
import pl.wsztajerowski.baas.cli.LoggingMixin;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.console.Hints;
import pl.wsztajerowski.baas.console.Json;
import pl.wsztajerowski.baas.console.Printable;
import pl.wsztajerowski.baas.deployment.setup.GitHubTrust;
import pl.wsztajerowski.baas.image.ImageBuilderService;
import pl.wsztajerowski.baas.image.RunnerImage;
import pl.wsztajerowski.baas.session.BaasConfig;
import pl.wsztajerowski.baas.session.CoreStack;
import pl.wsztajerowski.baas.session.DeployerSession;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * What is deployed, read and never changed (G14): the stack, the GitHub trust and the runner image,
 * under deployer credentials like every {@code admin} command. Three reads and a rendering, so no
 * port (ADR 0009): the rendering is a function of a {@link Report}, tested without AWS.
 */
@Command(
    name = "show",
    mixinStandardHelpOptions = true,
    description = "Report the deployment's server-side state: its stack, the GitHub repositories it trusts "
        + "and its runner image. Reads only."
)
public class DeploymentShowSubcommand implements Callable<Integer> {

    @Mixin LoggingMixin loggingMixin;

    @Option(names = "--format", description = "Output format: text (default), json.", defaultValue = "text")
    String format = "text";

    @Spec CommandSpec spec;

    @Mixin CommandContext context = new CommandContext();

    /** Everything the command reports; {@code github} and {@code image} are null when there is none. */
    record Report(String deployment, String region, CloudFormationService.StackView stack, GitHubTrust github,
                  RunnerImage image, String pointer) {
    }

    @Override
    public Integer call() {
        CommandRefusal.requireFormat(format, List.of("text", "json"));
        BaasConfig config = Invocation.configService(spec).load();
        String prefix = config.requirePrefix();
        var factory = DeployerSession.factory(config);

        CloudFormationService.StackView stack;
        try (var cf = factory.cloudFormation()) {
            stack = new CloudFormationService(cf).view(prefix).orElseThrow(() -> CommandRefusal.refused(
                "Stack " + prefix + " does not exist. Run `" + Hints.command(prefix, "admin deployment setup") + "` first."));
        }
        RunnerImage image;
        try (var imageBuilder = factory.imageBuilder(); var ec2 = factory.ec2(); var ssm = factory.ssm()) {
            image = new ImageBuilderService(imageBuilder, ec2, ssm).currentImage(config.amiParameterPath()).orElse(null);
        }
        var report = new Report(prefix, config.getAws().resolveRegion(), stack,
            GitHubTrust.deployed(stack.parameters()).orElse(null), image, config.amiParameterPath());

        if ("json".equalsIgnoreCase(format)) {
            context.console().println(json(report));
        } else {
            text(context.console(), report);
        }
        return 0;
    }

    /** Aligned label and value, like {@code config show}; the outputs last, one per line. */
    static void text(Console console, Report report) {
        var stack = report.stack();
        line(console, "Deployment", report.deployment() + " (" + report.region() + ")");
        line(console, "Stack", stack.status() + ", last updated " + stack.lastUpdated());
        line(console, "Set up by", cliVersion(report).map(v -> "baas " + v).orElse("(not recorded)"));
        line(console, "GitHub", report.github() == null ? "no repository may run benchmarks"
            : report.github().repos().stream().map(repo -> report.github().org() + "/" + repo)
                .collect(Collectors.joining(", ")) + " may run benchmarks");
        line(console, "Image", report.image() == null ? "none published (" + report.pointer() + ")"
            : orUnknown(report.image().imageVersion()) + " (" + report.image().amiId() + ")");
        console.println("Outputs");
        int width = stack.outputs().keySet().stream().mapToInt(String::length).max().orElse(0);
        stack.outputs().forEach((key, value) ->
            console.println("  " + key + " ".repeat(width - key.length() + 2) + Printable.of(value)));
    }

    private static void line(Console console, String label, String value) {
        console.println(label + " ".repeat(12 - label.length()) + Printable.of(value));
    }

    /** One object; {@code github} and {@code image} are null when there is none. */
    static String json(Report report) {
        var stack = report.stack();
        String github = report.github() == null ? "null"
            : "{\"org\":" + Json.string(report.github().org()) + ",\"repos\":"
                + report.github().repos().stream().map(Json::string).collect(Collectors.joining(",", "[", "]")) + "}";
        String image = report.image() == null ? "null"
            : "{\"version\":" + Json.string(report.image().imageVersion()) + ",\"amiId\":" + Json.string(report.image().amiId()) + "}";
        return "{\"deployment\":" + Json.string(report.deployment()) + ",\"region\":" + Json.string(report.region())
            + ",\"stack\":{\"status\":" + Json.string(stack.status()) + ",\"lastUpdated\":" + Json.string(stack.lastUpdated())
            + ",\"outputs\":" + object(stack.outputs()) + "}"
            + ",\"cliVersion\":" + Json.string(cliVersion(report).orElse(null))
            + ",\"github\":" + github + ",\"pointer\":" + Json.string(report.pointer()) + ",\"image\":" + image + "}";
    }

    private static String object(Map<String, String> values) {
        return values.entrySet().stream().map(e -> Json.string(e.getKey()) + ":" + Json.string(e.getValue()))
            .collect(Collectors.joining(",", "{", "}"));
    }

    private static java.util.Optional<String> cliVersion(Report report) {
        return java.util.Optional.ofNullable(report.stack().parameters().get(CoreStack.PARAM_BAAS_CLI_VERSION))
            .filter(version -> !version.isBlank());
    }

    private static String orUnknown(String value) {
        return value != null && !value.isEmpty() ? value : "(unknown)";
    }
}
