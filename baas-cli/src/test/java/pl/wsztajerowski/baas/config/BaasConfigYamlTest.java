package pl.wsztajerowski.baas.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BaasConfigYamlTest {

    // Mirrors the ObjectMapper configuration used by ConfigService when reading/writing
    // ~/.baas/config.yaml.
    private final ObjectMapper yaml = new ObjectMapper(
        new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER))
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void writesCoreStackNameFieldName() throws Exception {
        BaasConfig config = new BaasConfig();
        config.getAws().setCoreStackName("baas-a1b2c3d4");

        String written = yaml.writeValueAsString(config);

        assertThat(written).contains("coreStackName: \"baas-a1b2c3d4\"");
        assertThat(written).doesNotContain("stackName:");
    }

    @Test
    void roundTripsCoreStackName() throws Exception {
        BaasConfig original = new BaasConfig();
        original.getAws().setCoreStackName("baas-a1b2c3d4");

        String written = yaml.writeValueAsString(original);
        BaasConfig readBack = yaml.readValue(written, BaasConfig.class);

        assertThat(readBack.getAws().getCoreStackName()).isEqualTo("baas-a1b2c3d4");
    }
}
