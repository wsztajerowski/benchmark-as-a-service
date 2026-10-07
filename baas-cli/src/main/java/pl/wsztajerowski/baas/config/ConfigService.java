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

/**
 * Reads and writes one configuration file: {@code ~/.baas/config.yaml} unless {@code --config-path}
 * names another.
 *
 * <p>A missing file is treated differently by where its path came from. The default path missing is
 * an unconfigured machine — an empty config, and the "no deployment" error further on. A path the
 * operator typed missing is far more likely a typo, and reading it as an empty config would fail later
 * with an error about deployments rather than about the path, so a read through {@link #load()}
 * refuses it. The commands that create configuration use {@link #loadOrEmpty()} instead.
 *
 * <p>An unknown key is warned about and skipped, never fatal. Files written by older releases still
 * carry retired keys, so failing would break every upgrade; staying silent hid both a typo'd key and
 * a renamed one (a {@code wallClockHardKillSeconds} setting simply stopped applying). Any
 * {@link #save} drops them, since only known fields are written.
 */
public class ConfigService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigService.class);

    public static final Path DEFAULT_PATH = Path.of(System.getProperty("user.home"), ".baas", "config.yaml");

    private static final ObjectMapper YAML = new ObjectMapper(
        new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER))
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Path path;
    private final boolean explicit;

    /** The default file. */
    public ConfigService() {
        this(DEFAULT_PATH, false);
    }

    private ConfigService(Path path, boolean explicit) {
        this.path = path;
        this.explicit = explicit;
    }

    /** The file named by {@code --config-path}, or the default when none was given. */
    public static ConfigService at(Path explicitPath) {
        return explicitPath != null ? new ConfigService(explicitPath, true) : new ConfigService();
    }

    /** For commands that only read configuration: an explicitly named file must exist. */
    public BaasConfig load() {
        if (explicit && !Files.exists(path)) {
            throw new IllegalStateException("Configuration file not found: " + path);
        }
        return loadOrEmpty();
    }

    /** For commands that create configuration: {@code config sync}, {@code config set}, {@code admin deployment setup}. */
    public BaasConfig loadOrEmpty() {
        if (!Files.exists(path)) {
            return new BaasConfig();
        }
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

    public void save(BaasConfig config) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            YAML.writeValue(path.toFile(), config);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + path + ": " + e.getMessage(), e);
        }
    }

    public Path configFilePath() {
        return path;
    }
}
