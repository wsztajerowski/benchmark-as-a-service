package pl.wsztajerowski.baas.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigServiceTest {

    @TempDir
    Path root;

    private ConfigService unnamed() {
        return new ConfigService(root, Optional.empty());
    }

    private ConfigService named(String deployment) {
        return new ConfigService(root, Optional.of(deployment));
    }

    private void configure(String deployment) {
        BaasConfig config = new BaasConfig();
        config.setPrefix(deployment);
        unnamed().save(config);
    }

    // ─── Layout ──────────────────────────────────────────────────────────────────

    @Test
    void eachDeploymentIsItsOwnFileNamedByItsPrefix() {
        configure("wiktor-dev");

        assertThat(root.resolve("deployments").resolve("wiktor-dev.yaml")).exists();
        assertThat(unnamed().deployments()).containsExactly("wiktor-dev");
    }

    @Test
    void onlyYamlFilesInDeploymentsAreDeployments() throws Exception {
        configure("wiktor-dev");
        Files.writeString(root.resolve("deployments").resolve("notes.txt"), "x");
        Files.writeString(root.resolve("runner-image-extension.yaml"), "x");

        assertThat(unnamed().deployments()).containsExactly("wiktor-dev");
    }

    @Test
    void deleteRemovesOnlyThatDeploymentsFile() {
        configure("baas-123456789012");
        configure("wiktor-dev");

        unnamed().delete("wiktor-dev");

        assertThat(unnamed().deployments()).containsExactly("baas-123456789012");
    }

    // ─── Selection ───────────────────────────────────────────────────────────────

    @Test
    void theOnlyDeploymentIsImplied() {
        configure("baas-123456789012");

        assertThat(unnamed().load().getPrefix()).isEqualTo("baas-123456789012");
    }

    @Test
    void twoDeploymentsRequireANameAndAreListedWithTheHint() {
        configure("baas-123456789012");
        configure("wiktor-dev");

        assertThatThrownBy(() -> unnamed().load())
            .hasMessageContaining("2 deployments are configured (baas-123456789012, wiktor-dev)")
            .hasMessageContaining("→ choose one: baas config list");
        assertThat(named("wiktor-dev").load().getPrefix()).isEqualTo("wiktor-dev");
    }

    @Test
    void anUnknownNameNamesWhatIsConfigured() {
        configure("baas-123456789012");

        assertThatThrownBy(() -> named("other").load())
            .hasMessageContaining("No deployment 'other'")
            .hasMessageContaining("configured: baas-123456789012");
    }

    @Test
    void nothingConfiguredIsAnErrorForEveryCommandButSetup() {
        assertThatThrownBy(() -> unnamed().load()).hasMessageContaining("No deployment is configured");
        assertThatThrownBy(() -> unnamed().loadForSync()).hasMessageContaining("--deployment <name> config sync");
        assertThat(unnamed().loadForSetup().getPrefix()).as("setup derives the name").isNull();
    }

    @Test
    void setupAndSyncCreateANamedDeploymentBesideTheOthers() {
        configure("baas-123456789012");

        assertThat(named("wiktor-dev").loadForSetup().getPrefix()).isEqualTo("wiktor-dev");
        assertThat(named("wiktor-dev").loadForSync().getPrefix()).isEqualTo("wiktor-dev");
        assertThat(unnamed().deployments()).as("nothing is written until saved").containsExactly("baas-123456789012");
    }

    @Test
    void setupAndSyncWithoutANameUseTheOnlyOneAndRefuseTwo() {
        configure("baas-123456789012");
        assertThat(unnamed().loadForSetup().getPrefix()).isEqualTo("baas-123456789012");
        assertThat(unnamed().loadForSync().getPrefix()).isEqualTo("baas-123456789012");

        configure("wiktor-dev");
        assertThatThrownBy(() -> unnamed().loadForSetup()).hasMessageContaining("2 deployments");
        assertThatThrownBy(() -> unnamed().loadForSync()).hasMessageContaining("2 deployments");
    }

    // ─── Migration of the flat file ──────────────────────────────────────────────

    @Test
    void theFlatFileMovesToItsDeploymentsFileOnFirstLook() throws Exception {
        Files.writeString(root.resolve("config.yaml"), """
            prefix: "baas-123456789012"
            aws:
              operatorProfile: "baas-operator"
              region: "eu-central-1"
            """);

        BaasConfig config = unnamed().load();

        assertThat(config.getPrefix()).isEqualTo("baas-123456789012");
        assertThat(config.getAws().getOperatorProfile()).isEqualTo("baas-operator");
        assertThat(root.resolve("config.yaml")).doesNotExist();
        assertThat(root.resolve("deployments").resolve("baas-123456789012.yaml")).exists();
    }

    /** The flat file can only be newer: an older CLI wrote it after an earlier migration. */
    @Test
    void theFlatFileOverwritesAnExistingDeploymentFile() throws Exception {
        BaasConfig older = new BaasConfig();
        older.setPrefix("baas-123456789012");
        older.getEc2().setDefaultInstanceType("c5.large");
        unnamed().save(older);
        Files.writeString(root.resolve("config.yaml"), """
            prefix: "baas-123456789012"
            ec2:
              defaultInstanceType: "c6i.4xlarge"
            """);

        assertThat(unnamed().load().getEc2().getDefaultInstanceType()).isEqualTo("c6i.4xlarge");
        assertThat(root.resolve("config.yaml")).doesNotExist();
    }

    @Test
    void aFlatFileWithoutAPrefixIsLeftAloneAndIgnored() throws Exception {
        Path flat = root.resolve("config.yaml");
        Files.writeString(flat, "ec2:\n  defaultInstanceType: \"c5.large\"\n");

        assertThat(unnamed().deployments()).isEmpty();
        assertThat(flat).exists();
    }
}
