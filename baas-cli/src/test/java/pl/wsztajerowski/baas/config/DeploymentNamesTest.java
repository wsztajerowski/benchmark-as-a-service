package pl.wsztajerowski.baas.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentNamesTest {

    @Test
    void theDefaultDeploymentComposesEveryNameFromItsPrefix() {
        DeploymentNames names = DeploymentNames.of("baas-123456789012");

        assertThat(names.stack()).isEqualTo("baas-123456789012");
        assertThat(names.bucket()).isEqualTo("baas-123456789012");
        assertThat(names.resultsTable()).isEqualTo("baas-123456789012-results");
        assertThat(names.amiPointer()).isEqualTo("/baas-123456789012/runner/ami-id");
        assertThat(names.runnerRole()).isEqualTo("baas-123456789012-role-runner");
        assertThat(names.operatorRole()).isEqualTo("baas-123456789012-role-operator");
        assertThat(names.imageBuildRole()).isEqualTo("baas-123456789012-role-image-build");
        assertThat(names.runnerInstanceProfile()).isEqualTo("baas-123456789012-profile-runner");
        assertThat(names.runnerComponent()).isEqualTo("baas-123456789012-component-runner");
        assertThat(names.runnerRecipe()).isEqualTo("baas-123456789012-recipe-runner");
    }

    /** A named deployment's prefix is used verbatim: nothing prepends the `baas-` namespace. */
    @Test
    void aNamedDeploymentIsUsedVerbatim() {
        DeploymentNames names = DeploymentNames.of("wiktor-dev");

        assertThat(names.stack()).isEqualTo("wiktor-dev");
        assertThat(names.resultsTable()).isEqualTo("wiktor-dev-results");
        assertThat(names.runnerRole()).isEqualTo("wiktor-dev-role-runner");
        assertThat(names.amiPointer()).isEqualTo("/wiktor-dev/runner/ami-id");
    }

    @Test
    void theLongestPrefixStillFitsEveryRoleNameInIamsLimit() {
        String longest = "a".repeat(DeploymentNames.MAX_PREFIX_LENGTH);
        DeploymentNames names = DeploymentNames.of(longest);

        assertThat(DeploymentNames.MAX_PREFIX_LENGTH).isEqualTo(47);
        assertThat(names.imageBuildRole()).hasSize(64);
        assertThat(names.runnerRole()).hasSizeLessThanOrEqualTo(64);
        assertThat(names.operatorRole()).hasSizeLessThanOrEqualTo(64);
    }

    @Test
    void theConfigurationDerivesThroughTheSameNames() {
        BaasConfig config = new BaasConfig();
        config.setPrefix("wiktor-dev");

        assertThat(config.names()).isEqualTo(DeploymentNames.of("wiktor-dev"));
        assertThat(config.resultsTable()).isEqualTo("wiktor-dev-results");
    }
}
