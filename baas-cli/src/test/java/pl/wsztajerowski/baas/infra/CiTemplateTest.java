package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

class CiTemplateTest {

    @Test
    void grantsBothSingularAndPluralSsmReadsForTheAmiLookup() {
        assertThat(workflowRoleActions())
            .as("GetParameter and GetParameters are distinct IAM actions; the core stack uses the singular form")
            .contains("ssm:GetParameter", "ssm:GetParameters");
    }

    @Test
    void canReadBackWhatItWrites() {
        assertThat(workflowRoleActions()).contains("s3:GetObject");
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

    /** {@code ci/} is retired — a CI run is a run, and writes under {@code runs/} like any other. */
    @Test
    void theWorkflowRoleHasNoGrantForTheRetiredCiPrefix() {
        assertThat(workflowRoleResources())
            .noneMatch(resource -> resource.contains("/ci/*"))
            .anyMatch(resource -> resource.contains("/runs/*"));
    }

    @SuppressWarnings("unchecked")
    private Set<String> workflowRoleResources() {
        var policies = (List<Map<String, Object>>)
            InfraFixtures.properties(InfraFixtures.ciTemplate(), "WorkflowRole").get("Policies");

        Set<String> resources = new TreeSet<>();
        for (Map<String, Object> policy : policies) {
            var document = (Map<String, Object>) policy.get("PolicyDocument");
            for (Map<String, Object> statement : (List<Map<String, Object>>) document.get("Statement")) {
                Object resource = statement.get("Resource");
                if (resource instanceof List<?> list) {
                    list.forEach(entry -> resources.add(String.valueOf(entry)));
                } else if (resource != null) {
                    resources.add(String.valueOf(resource));
                }
            }
        }
        return resources;
    }

    @SuppressWarnings("unchecked")
    private Set<String> workflowRoleActions() {
        var policies = (List<Map<String, Object>>)
            InfraFixtures.properties(InfraFixtures.ciTemplate(), "WorkflowRole").get("Policies");

        Set<String> actions = new TreeSet<>();
        for (Map<String, Object> policy : policies) {
            actions.addAll(InfraFixtures.actions((Map<String, Object>) policy.get("PolicyDocument")));
        }
        return actions;
    }
}
