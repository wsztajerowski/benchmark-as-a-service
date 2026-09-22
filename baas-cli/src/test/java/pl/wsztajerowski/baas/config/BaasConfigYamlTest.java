package pl.wsztajerowski.baas.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BaasConfigYamlTest {

    // Mirrors the ObjectMapper configuration used by ConfigService when reading/writing
    // ~/.baas/config.yaml.
    private final ObjectMapper yaml = new ObjectMapper(
        new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER))
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * {@code aws.coreStackName} is gone: the stack name and the installation prefix are the same
     * string now, and storing both invited them to disagree.
     */
    @Test
    void theStackNameIsNoLongerAStoredField() throws Exception {
        BaasConfig config = new BaasConfig();
        config.setPrefix("baas-123456789012");

        String written = yaml.writeValueAsString(config);

        assertThat(written).contains("prefix: \"baas-123456789012\"");
        assertThat(written).doesNotContain("coreStackName:");
        assertThat(written).doesNotContain("stackName:");
    }

    /**
     * Names the composition rule fixes are derived, not stored. Writing them into the file lets a
     * stale copy point at a different installation than {@code prefix} names.
     */
    @Test
    void namesTheCompositionRuleFixesAreNotStored() throws Exception {
        BaasConfig config = new BaasConfig();
        config.setPrefix("baas-123456789012");

        String written = yaml.writeValueAsString(config);

        assertThat(written)
            .doesNotContain("bucket:")
            .doesNotContain("resultsTable:")
            .doesNotContain("runnerInstanceProfileName:")
            .doesNotContain("vpcId:")
            .doesNotContain("subnetId:")
            .doesNotContain("securityGroupId:")
            .doesNotContain("asyncProfilerVersion:");
    }

    @Test
    void derivesEveryNameFromThePrefix() {
        BaasConfig config = new BaasConfig();
        config.setPrefix("baas-123456789012");

        assertThat(config.stackName()).isEqualTo("baas-123456789012");
        assertThat(config.bucket()).isEqualTo("baas-123456789012");
        assertThat(config.resultsTable()).isEqualTo("baas-123456789012-results");
        assertThat(config.runnerInstanceProfile()).isEqualTo("baas-123456789012-profile-runner");
        assertThat(config.amiParameterPath()).isEqualTo("/baas-123456789012/runner/ami-id");
    }

    @Test
    void theDevInstallationDerivesItsOwnNames() {
        BaasConfig config = new BaasConfig();
        config.setPrefix("baas-123456789012-dev");

        assertThat(config.bucket()).isEqualTo("baas-123456789012-dev");
        assertThat(config.resultsTable()).isEqualTo("baas-123456789012-dev-results");
    }

    /**
     * There is no default installation. A machine that has never run setup or sync must say so
     * rather than derive names for something that does not exist.
     */
    @Test
    void anUnconfiguredInstallationIsAHardFailureRatherThanADefault() {
        BaasConfig config = new BaasConfig();

        assertThat(config.getPrefix()).isNull();
        assertThatThrownBy(config::resultsTable)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("baas config sync --name");
    }

    @Test
    void roundTripsThePrefix() throws Exception {
        BaasConfig original = new BaasConfig();
        original.setPrefix("baas-123456789012-dev");

        BaasConfig readBack = yaml.readValue(yaml.writeValueAsString(original), BaasConfig.class);

        assertThat(readBack.getPrefix()).isEqualTo("baas-123456789012-dev");
    }

    /**
     * The runner's source repository used to be a string baked into the user-data shell script
     * (finding A7), so a fork could not point the CLI at its own releases.
     */
    @Test
    void defaultsTheRunnerSourceRepositoryToUpstream() {
        assertThat(new BaasConfig().getRunner().getSourceRepo())
            .isEqualTo("wsztajerowski/benchmark-as-a-service");
    }

    @Test
    void roundTripsAnOverriddenRunnerSourceRepository() throws Exception {
        BaasConfig original = new BaasConfig();
        original.getRunner().setSourceRepo("acme/baas-fork");

        BaasConfig readBack = yaml.readValue(yaml.writeValueAsString(original), BaasConfig.class);

        assertThat(readBack.getRunner().getSourceRepo()).isEqualTo("acme/baas-fork");
    }
}
