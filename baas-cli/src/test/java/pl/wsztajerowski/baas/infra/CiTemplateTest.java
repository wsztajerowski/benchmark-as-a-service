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
