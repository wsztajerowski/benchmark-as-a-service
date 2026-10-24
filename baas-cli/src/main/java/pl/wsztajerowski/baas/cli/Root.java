package pl.wsztajerowski.baas.cli;

import java.nio.file.Path;
import java.util.Optional;

/**
 * What a command needs from the root of the command tree: where the deployments' files live, the
 * {@code --deployment} it was given, and the one place {@code -v} is recorded. {@code BaasApp}
 * implements it; commands reach it through {@link Invocation}, so they depend on this package and
 * never on the composition root that registers them.
 */
public interface Root {

    /** {@code ~/.baas}, or a test's own directory. */
    Path configRoot();

    /** The {@code --deployment} the command line named, stripped, if any. */
    Optional<String> deploymentOption();

    /** The root's own mixin, where {@code -v} is stored whichever subcommand it was typed after. */
    LoggingMixin rootLoggingMixin();
}
