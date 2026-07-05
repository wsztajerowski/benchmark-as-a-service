package pl.wsztajerowski.baas.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigService {

    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".baas");
    static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.yaml");

    private static final ObjectMapper YAML = new ObjectMapper(
        new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER))
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public BaasConfig load() {
        if (!Files.exists(CONFIG_FILE)) {
            return new BaasConfig();
        }
        try {
            return YAML.readValue(CONFIG_FILE.toFile(), BaasConfig.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + CONFIG_FILE + ": " + e.getMessage(), e);
        }
    }

    public void save(BaasConfig config) {
        try {
            Files.createDirectories(CONFIG_DIR);
            YAML.writeValue(CONFIG_FILE.toFile(), config);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + CONFIG_FILE + ": " + e.getMessage(), e);
        }
    }

    public Path configFilePath() {
        return CONFIG_FILE;
    }
}
