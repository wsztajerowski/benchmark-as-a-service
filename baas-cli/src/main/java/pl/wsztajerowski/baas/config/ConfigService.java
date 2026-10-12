package pl.wsztajerowski.baas.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * This machine's deployments: one file per deployment, {@code <root>/deployments/<name>.yaml}, where
 * {@code <root>} is {@code ~/.baas}. The deployment's name is the file's name and nothing else: the
 * file does not record it, so a copy under another name can never aim at the original.
 *
 * <p>No file records a default deployment. When {@code --deployment} is absent, the only configured
 * deployment is used; with two or more the command must name one. With none, setup derives the name
 * from the account and a day-to-day command takes it from an operator-role session
 * ({@code OperatorSession}); anything else fails. Nothing else selects a deployment — no environment variable, no
 * stored pointer, no switch command — so what a command hits is always visible on its command line
 * or unambiguous on the machine.
 *
 * <p>One file per deployment rather than one file with a map: an older CLI's save drops the keys it
 * does not know, so a map of deployments in a shared file would vanish the first time an older CLI
 * ran {@code config set}. An older CLI never opens these files at all.
 *
 * <p>An unknown key is warned about and skipped, never fatal. Files written by older releases still
 * carry retired keys, so failing would break every upgrade; staying silent hid both a typo'd key and
 * a renamed one (a {@code wallClockHardKillSeconds} setting simply stopped applying). Any
 * {@link #save} drops them, since only known fields are written.
 */
public class ConfigService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigService.class);

    static final String DEPLOYMENTS_DIR = "deployments";
    static final String SUFFIX = ".yaml";

    private static final ObjectMapper YAML = new ObjectMapper(
        new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER))
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Path root;
    private final Optional<String> named;

    /**
     * @param root  the directory holding {@code deployments/}; {@code ~/.baas} outside tests
     * @param named the deployment {@code --deployment} named, if any
     */
    public ConfigService(Path root, Optional<String> named) {
        this.root = root;
        this.named = named;
    }

    /** {@code ~/.baas}, read when asked rather than at class load, so a test's {@code user.home} holds. */
    public static Path defaultRoot() {
        return Path.of(System.getProperty("user.home"), ".baas");
    }

    public Path root() {
        return root;
    }

    /** The deployment {@code --deployment} named, if any. */
    public Optional<String> named() {
        return named;
    }

    public Path fileOf(String deployment) {
        return root.resolve(DEPLOYMENTS_DIR).resolve(deployment + SUFFIX);
    }

    /** The configured deployments' names, sorted. */
    public List<String> deployments() {
        Path dir = root.resolve(DEPLOYMENTS_DIR);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files
                .filter(Files::isRegularFile)
                .map(file -> file.getFileName().toString())
                .filter(name -> name.endsWith(SUFFIX))
                .map(name -> name.substring(0, name.length() - SUFFIX.length()))
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to list " + dir + ": " + e.getMessage(), e);
        }
    }

    /** Every configured deployment's configuration, in name order — what {@code config list} shows. */
    public List<BaasConfig> all() {
        return deployments().stream().map(name -> readDeployment(name)).toList();
    }

    /**
     * The deployment a command addresses: the one {@code --deployment} names, or the only one
     * configured. Fails with a message naming what is configured otherwise.
     */
    public BaasConfig load() {
        List<String> configured = deployments();
        if (named.isPresent()) {
            String name = named.get();
            if (!configured.contains(name)) {
                throw new DeploymentSelectionException(unknown(name, configured));
            }
            return readDeployment(name);
        }
        return switch (configured.size()) {
            case 0 -> throw new DeploymentSelectionException(noneConfigured());
            case 1 -> readDeployment(configured.getFirst());
            default -> throw new DeploymentSelectionException(ambiguous(configured));
        };
    }

    /**
     * For {@code admin deployment setup}: the named deployment, existing or new; else the only one;
     * else, with none configured, an empty configuration whose name setup derives from the account.
     */
    public BaasConfig loadForSetup() {
        List<String> configured = deployments();
        if (named.isPresent()) {
            return existingOrNew(named.get(), configured);
        }
        return switch (configured.size()) {
            case 0 -> new BaasConfig();
            case 1 -> readDeployment(configured.getFirst());
            default -> throw new DeploymentSelectionException(ambiguous(configured));
        };
    }

    /**
     * For {@code config sync}: the named deployment, existing or new; else the only one. Never
     * derived — a machine with nothing configured must say which deployment it adopts, or a wrong
     * role or a leftover {@code AWS_PROFILE} would bind it to whichever deployment those imply.
     */
    public BaasConfig loadForSync() {
        List<String> configured = deployments();
        if (named.isPresent()) {
            return existingOrNew(named.get(), configured);
        }
        return switch (configured.size()) {
            case 0 -> throw new DeploymentSelectionException("""
                No deployment is configured on this machine, so config sync must be told which to adopt:
                  baas --deployment <name> config sync""");
            case 1 -> readDeployment(configured.getFirst());
            default -> throw new DeploymentSelectionException(ambiguous(configured));
        };
    }

    private BaasConfig existingOrNew(String name, List<String> configured) {
        if (configured.contains(name)) {
            return readDeployment(name);
        }
        BaasConfig fresh = new BaasConfig();
        fresh.setPrefix(name);
        return fresh;
    }

    /** Writes the deployment's own file, named for the deployment. */
    public void save(BaasConfig config) {
        write(fileOf(config.requirePrefix()), config);
    }

    /** Removes a torn-down deployment's file; absent already is not an error. */
    public void delete(String deployment) {
        try {
            Files.deleteIfExists(fileOf(deployment));
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete " + fileOf(deployment) + ": " + e.getMessage(), e);
        }
    }

    /** Why a command cannot run with no deployment configured. */
    public static String noneConfigured() {
        return """
            No deployment is configured on this machine.
              Adopt one:  baas --deployment <name> config sync
              Create one: baas admin deployment setup""";
    }

    static String ambiguous(List<String> configured) {
        return "%d deployments are configured (%s); name one with --deployment.%n→ choose one: baas config list"
            .formatted(configured.size(), String.join(", ", configured));
    }

    static String unknown(String name, List<String> configured) {
        String which = configured.isEmpty() ? "none" : String.join(", ", configured);
        return "No deployment '%s' is configured on this machine (configured: %s). Adopt it with: baas --deployment %s config sync"
            .formatted(name, which, name);
    }

    /** A deployment's file, named for the deployment it configures. */
    private BaasConfig readDeployment(String name) {
        BaasConfig config = readLogged(fileOf(name));
        config.setPrefix(name);
        return config;
    }

    private BaasConfig readLogged(Path path) {
        Read read = read(path);
        read.unknownKeys().forEach(key -> logger.warn("Ignoring unknown key '{}' in {}", key, path));
        return read.config();
    }

    record Read(BaasConfig config, List<String> unknownKeys) {}

    /** Split out from the logging so the unknown-key detection is testable without capturing stderr. */
    static Read read(Path path) {
        List<String> unknownKeys = new ArrayList<>();
        DeserializationProblemHandler collector = new DeserializationProblemHandler() {
            @Override
            public boolean handleUnknownProperty(DeserializationContext ctxt, JsonParser p,
                                                 JsonDeserializer<?> deserializer, Object beanOrClass,
                                                 String propertyName) throws IOException {
                // The parser sits on the unknown property, so its pointer is the full dotted path.
                unknownKeys.add(p.getParsingContext().pathAsPointer().toString().substring(1).replace('/', '.'));
                p.skipChildren();
                return true;
            }
        };
        try {
            BaasConfig config = YAML.readerFor(BaasConfig.class).withHandler(collector).readValue(path.toFile());
            return new Read(config, unknownKeys);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + path + ": " + e.getMessage(), e);
        }
    }

    private static void write(Path path, BaasConfig config) {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            YAML.writeValue(path.toFile(), config);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + path + ": " + e.getMessage(), e);
        }
    }
}
