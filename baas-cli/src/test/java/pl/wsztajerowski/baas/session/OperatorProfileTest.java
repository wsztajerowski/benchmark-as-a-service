package pl.wsztajerowski.baas.session;

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
    void theOperatorProfileIsTheSourceWhenSet() {
        var aws = new BaasConfig.AwsConfig();
        aws.setDeployerProfile("baas-deployer");
        aws.setOperatorProfile("baas-operator");

        assertThat(aws.operatorSourceProfile()).isEqualTo("baas-operator");
    }

    /**
     * The fallback the old rule forbade. It is safe now: the source only ever assumes the operator
     * role, so deployer rights never reach a day-to-day command (OperatorSessionTest pins that).
     */
    @Test
    void theDeployerProfileIsTheSourceWhenNoOperatorProfileIsSet() {
        var aws = new BaasConfig.AwsConfig();
        aws.setDeployerProfile("baas-deployer");

        assertThat(aws.operatorSourceProfile()).isEqualTo("baas-deployer");
    }

    @Test
    void withNeitherTheSourceIsTheDefaultChain() {
        assertThat(new BaasConfig.AwsConfig().operatorSourceProfile()).isNull();
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
