package pl.wsztajerowski.baas.cli;

import picocli.CommandLine.Model.CommandSpec;
import pl.wsztajerowski.baas.session.ConfigService;

import java.util.Optional;

/**
 * This invocation's view of the root: read from the command tree at call time, never in a field
 * initialiser — fields are initialised while picocli builds the tree, before {@code --deployment} is
 * parsed. A command constructed outside a tree (a unit test) gets the default root and no name.
 */
public final class Invocation {

    private Invocation() {
    }

    /** The {@code --deployment} the command line named, if any. */
    public static Optional<String> deployment(CommandSpec spec) {
        return root(spec).flatMap(Root::deploymentOption);
    }

    /** The deployments a command works with, bound to the {@code --deployment} it was given. */
    public static ConfigService configService(CommandSpec spec) {
        return root(spec)
            .map(root -> new ConfigService(root.configRoot(), root.deploymentOption()))
            .orElseGet(() -> new ConfigService(ConfigService.defaultRoot(), Optional.empty()));
    }

    static Optional<Root> root(CommandSpec spec) {
        return spec != null && spec.root().userObject() instanceof Root root ? Optional.of(root) : Optional.empty();
    }
}
