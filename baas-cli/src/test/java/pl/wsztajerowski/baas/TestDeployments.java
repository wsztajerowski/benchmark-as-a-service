package pl.wsztajerowski.baas;

import pl.wsztajerowski.baas.config.BaasConfig;
import pl.wsztajerowski.baas.config.ConfigService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Deployment files under a test's own root, for a {@code new BaasApp(root)} command tree to read.
 * Never {@code ~/.baas}: the real one belongs to the developer running the build.
 */
public final class TestDeployments {

    public static final String DEFAULT = "baas-123456789012";

    private TestDeployments() {
    }

    /** {@code <root>/deployments/<name>.yaml} with the given YAML body, which should set {@code prefix}. */
    public static Path writeRaw(Path root, String name, String yaml) throws IOException {
        Path file = root.resolve("deployments").resolve(name + ".yaml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml);
        return file;
    }

    /** A minimal deployment: its prefix and region. */
    public static Path write(Path root, String name) throws IOException {
        return writeRaw(root, name, "prefix: \"%s\"\naws:\n  region: \"eu-central-1\"\n".formatted(name));
    }

    /** Saves a configuration as its own deployment's file, the way the CLI does. */
    public static Path save(Path root, BaasConfig config) {
        var service = new ConfigService(root, Optional.empty());
        service.save(config);
        return service.fileOf(config.requirePrefix());
    }
}
