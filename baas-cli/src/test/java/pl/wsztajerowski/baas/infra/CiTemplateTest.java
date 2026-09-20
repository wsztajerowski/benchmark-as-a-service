package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CI stack is now the identity provider and nothing else. {@code WorkflowRole} moved out
 * entirely: GitHub Actions federates directly into {@code BaasCliOperatorRole}, which the core
 * stack owns — see {@link CoreTemplateTest}.
 */
class CiTemplateTest {

    @Test
    @SuppressWarnings("unchecked")
    void theCiStackDeclaresTheIdentityProviderAndNothingElse() {
        var resources = (Map<String, Object>) InfraFixtures.ciTemplate().get("Resources");

        assertThat(resources).containsOnlyKeys("GithubOidc");
        assertThat(InfraFixtures.resource(InfraFixtures.ciTemplate(), "GithubOidc"))
            .containsEntry("Type", "AWS::IAM::OIDCProvider");
    }

    /**
     * A role-chained session is capped at 60 minutes by STS whatever MaxSessionDuration says,
     * against a 7200 s default benchmark timeout — and the failure mode was a red job with a good
     * measurement, an un-terminated instance and a full EC2 bill.
     */
    @Test
    @SuppressWarnings("unchecked")
    void noIamRoleRemainsInTheCiStack() {
        var resources = (Map<String, Object>) InfraFixtures.ciTemplate().get("Resources");

        assertThat(resources.values())
            .as("an intermediate role between the workload identity and the operator role is "
                + "exactly what this change removed")
            .noneMatch(resource -> "AWS::IAM::Role"
                .equals(((Map<String, Object>) resource).get("Type")));
    }

    /**
     * The provider is account-global and the core stack's trust policy references its ARN, so it
     * outlives any one stack: a delete that took it with it would break every installation
     * federating through it.
     */
    @Test
    void theIdentityProviderSurvivesAStackDelete() {
        assertThat(InfraFixtures.resource(InfraFixtures.ciTemplate(), "GithubOidc"))
            .containsEntry("DeletionPolicy", "Retain");
    }

    /**
     * Deploy order inverts: the provider comes first and its ARN is handed to the core stack. So
     * this template must take nothing from core — a parameter naming a core resource would make
     * the two circular.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theCiStackTakesNoParameterFromTheCoreStack() {
        var parameters = (Map<String, Object>) InfraFixtures.ciTemplate().get("Parameters");

        assertThat(parameters).containsOnlyKeys("ResourceNamePrefix");
    }

    /**
     * The CI template and the IAM policy JSONs are test fixtures — only the core template is
     * a runtime resource that {@code baas admin setup} reads out of the shipped JAR. Copying
     * them into {@code src/main/resources} would leak the CI stack's definition into every
     * distributed artifact.
     */
    @Test
    void onlyTheCoreTemplateIsOnTheRuntimeClasspath() {
        assertThat(getClass().getResourceAsStream("/templates/cf-template-core.yaml"))
            .as("baas admin setup reads this out of the JAR at runtime")
            .isNotNull();
        assertThat(getClass().getResourceAsStream("/templates/cf-template-ci.yaml"))
            .as("the CI template is a fixture under /infra, never shipped under /templates")
            .isNull();
    }
}
