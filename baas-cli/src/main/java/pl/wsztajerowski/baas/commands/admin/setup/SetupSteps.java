package pl.wsztajerowski.baas.commands.admin.setup;

import pl.wsztajerowski.baas.config.ConfigService;
import pl.wsztajerowski.baas.console.Console;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/** The walkthrough's eleven steps, in the order the user meets them, and how to run them. */
public final class SetupSteps {

    private SetupSteps() {}

    static List<SetupStep> all() {
        return List.of(
            new DeploymentStep(),
            new CredentialsStep(),
            new NameAndRegionStep(),
            new PermissionsStep(),
            new NamesStep(),
            new GitHubStep(),
            new PlanStep(),
            new StackStep(),
            new OperatorRoleStep(),
            new ImageStep(),
            new DoneStep());
    }

    /**
     * @param console     standard output; interactive only when it is a terminal
     * @param interactive whether to ask and wait — the console's answer, unless {@code --non-interactive}
     * @param reader      reads one line of input; {@code null} on a closed input
     * @param awsFactory  AWS for a region and a deployer profile ({@code null} for the default chain)
     * @return the exit code
     */
    public static int run(SetupOptions options, ConfigService configs, Console console, boolean interactive,
                          Supplier<String> reader, BiFunction<String, String, SetupAws> awsFactory) throws Exception {
        var context = new SetupContext(options, configs, interactive, new Prompter(console, reader), console,
            awsFactory, duration -> Thread.sleep(duration.toMillis()));
        return new SetupWalkthrough(all()).run(context);
    }
}
