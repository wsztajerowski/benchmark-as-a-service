package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

class OperatorPolicyDriftTest {

    @Test
    void referenceCopyGrantsTheSameActionsAsTheStackRole() {
        Set<String> fromTemplate = operatorRoleActions();
        Set<String> fromReferenceCopy = InfraFixtures.actions(InfraFixtures.operatorPolicy());

        assertThat(fromReferenceCopy)
            .as("infra/operator-policy.json documents OperatorRole — the two must not drift apart")
            .isEqualTo(fromTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void passRoleIsPinnedToASingleAccount() {
        var statements = (List<Map<String, Object>>) InfraFixtures.operatorPolicy().get("Statement");
        var passRole = statements.stream()
            .filter(statement -> "PassRunnerRoleOnly".equals(statement.get("Sid")))
            .findFirst()
            .orElseThrow();

        assertThat((String) passRole.get("Resource"))
            .as("a wildcard account id would let this policy pass roles in someone else's account")
            .doesNotContain("::*:");
    }

    /** Flattens the actions across every inline policy attached to OperatorRole. */
    @SuppressWarnings("unchecked")
    private Set<String> operatorRoleActions() {
        var policies = (List<Map<String, Object>>)
            InfraFixtures.properties(InfraFixtures.coreTemplate(), "OperatorRole").get("Policies");

        Set<String> actions = new TreeSet<>();
        for (Map<String, Object> policy : policies) {
            actions.addAll(InfraFixtures.actions((Map<String, Object>) policy.get("PolicyDocument")));
        }
        return actions;
    }
}
