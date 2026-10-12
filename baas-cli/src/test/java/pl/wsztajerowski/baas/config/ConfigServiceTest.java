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

    // ─── The file's name is the deployment's name ────────────────────────────────

    /** Spec: each deployment has one configuration file, named by the deployment. */
    @Test
    void aSavedFileDoesNotRecordTheName() throws Exception {
        configure("wiktor-dev");

        assertThat(Files.readString(root.resolve("deployments").resolve("wiktor-dev.yaml")))
            .doesNotContain("prefix").doesNotContain("wiktor-dev");
    }

    /** A copy under another name configures that other deployment: it carries no name to aim back with. */
    @Test
    void aCopiedFileTakesItsNewName() throws Exception {
        configure("baas-123456789012");
        Path deployments = root.resolve("deployments");
        Files.copy(deployments.resolve("baas-123456789012.yaml"), deployments.resolve("wiktor-dev.yaml"));

        assertThat(named("wiktor-dev").load().getPrefix()).isEqualTo("wiktor-dev");
    }

    @Test
    void aFileWithoutAPrefixTakesItsName() throws Exception {
        Files.createDirectories(root.resolve("deployments"));
        Files.writeString(root.resolve("deployments").resolve("wiktor-dev.yaml"), "aws:\n  region: us-east-1\n");

        assertThat(named("wiktor-dev").load().getPrefix()).isEqualTo("wiktor-dev");
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

    // ─── No flat file ────────────────────────────────────────────────────────────

    /** The flat file's migration is gone with the rebuild: a stray one is neither read nor removed. */
    @Test
    void aStrayFlatFileIsNeitherReadNorRemoved() throws Exception {
        Path flat = root.resolve("config.yaml");
        Files.writeString(flat, "prefix: \"baas-123456789012\"\naws:\n  region: \"eu-central-1\"\n");

        assertThat(unnamed().deployments()).isEmpty();
        assertThatThrownBy(() -> unnamed().load()).isInstanceOf(DeploymentSelectionException.class);
        assertThat(flat).exists();
    }
}
