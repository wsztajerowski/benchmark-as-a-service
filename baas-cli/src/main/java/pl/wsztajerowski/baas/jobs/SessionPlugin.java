package pl.wsztajerowski.baas.jobs;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * AWS's {@code session-manager-plugin}, which carries a Session Manager terminal stream. Only
 * {@code baas jobs connect} needs it, so it is looked for there, before any AWS call, and never
 * installed by {@code baas}: it is Amazon's binary, not a BaaS artifact.
 */
final class SessionPlugin {

    static final String NAME = "session-manager-plugin";

    private SessionPlugin() {}

    /** The first executable {@value #NAME} on {@code path}, a {@link File#pathSeparator}-joined list. */
    static Optional<Path> find(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Path.of(dir, NAME);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** What to say when it is missing, naming the install for the operator's platform. */
    static String missing(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        String install = os.contains("mac")
            ? "brew install --cask session-manager-plugin"
            : "the RPM or DEB package from https://docs.aws.amazon.com/systems-manager/latest/userguide/"
                + "session-manager-working-with-install-plugin.html";
        return NAME + " is not on PATH; `baas jobs connect` needs it to carry the shell. Install it with "
            + install + ", then run the command again. Nothing was started.";
    }
}
