package pl.wsztajerowski.baas.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OperatorProfileTest {

    private final ObjectMapper yaml = new ObjectMapper(
        new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER))
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void resolvesTheOperatorProfileWhenSet() {
        var aws = new BaasConfig.AwsConfig();
        aws.setProfile("baas-deployer");
        aws.setOperatorProfile("baas-operator");

        assertThat(aws.resolveOperatorProfile()).isEqualTo("baas-operator");
    }

    @Test
    void neverFallsBackToTheDeployerProfile() {
        var aws = new BaasConfig.AwsConfig();
        aws.setProfile("baas-deployer");

        assertThat(aws.resolveOperatorProfile())
            .as("falling back to the deployer profile is exactly the privilege leak this field exists to close")
            .isNull();
    }

    @Test
    void roundTripsOperatorProfile() throws Exception {
        BaasConfig original = new BaasConfig();
        original.getAws().setOperatorProfile("baas-operator");

        String written = yaml.writeValueAsString(original);
        BaasConfig readBack = yaml.readValue(written, BaasConfig.class);

        assertThat(written).contains("operatorProfile: \"baas-operator\"");
        assertThat(readBack.getAws().getOperatorProfile()).isEqualTo("baas-operator");
    }
}
