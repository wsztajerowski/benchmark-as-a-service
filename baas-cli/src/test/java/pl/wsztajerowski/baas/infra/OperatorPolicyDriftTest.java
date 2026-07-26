package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class OperatorPolicyDriftTest {

    @Test
    void referenceCopyGrantsTheSameActionsAsTheStackRole() {
        Set<String> fromTemplate = new TreeSet<>();
        operatorRoleStatements().forEach(statement -> fromTemplate.addAll(strings(statement.get("Action"))));
        Set<String> fromReferenceCopy = InfraFixtures.actions(InfraFixtures.operatorPolicy());

        assertThat(fromReferenceCopy)
            .as("infra/operator-policy.json documents OperatorRole — the two must not drift apart")
            .isEqualTo(fromTemplate);
    }

    /**
     * Comparing action names alone lets resources and conditions drift silently, and those
     * carry the actual security properties — a matching action list with a wildcard resource
     * documents something the stack does not grant.
     */
    @Test
    void referenceCopyGrantsTheSameResourcesAndConditionsAsTheStackRole() {
        Set<String> fromTemplate = operatorRoleStatements().stream()
            .map(OperatorPolicyDriftTest::canonical)
            .collect(Collectors.toCollection(TreeSet::new));

        Set<String> fromReferenceCopy = referenceStatements().stream()
            .map(OperatorPolicyDriftTest::canonical)
            .collect(Collectors.toCollection(TreeSet::new));

        assertThat(fromReferenceCopy)
            .as("resources and conditions drifted — substitute the tokens the same way the "
                + "template resolves them, or the reference copy misrepresents the real role")
            .isEqualTo(fromTemplate);
    }

    @Test
    void passRoleIsPinnedToASingleAccount() {
        var passRole = referenceStatements().stream()
            .filter(statement -> "PassRunnerRoleOnly".equals(statement.get("Sid")))
            .findFirst()
            .orElseThrow();

        assertThat((String) passRole.get("Resource"))
            .as("a wildcard account id would let this policy pass roles in someone else's account")
            .doesNotContain("::*:");
    }

    /** Every statement across every inline policy attached to OperatorRole. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> operatorRoleStatements() {
        var policies = (List<Map<String, Object>>)
            InfraFixtures.properties(InfraFixtures.coreTemplate(), "OperatorRole").get("Policies");

        return policies.stream()
            .map(policy -> (Map<String, Object>) policy.get("PolicyDocument"))
            .flatMap(document -> ((List<Map<String, Object>>) document.get("Statement")).stream())
            .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> referenceStatements() {
        return (List<Map<String, Object>>) InfraFixtures.operatorPolicy().get("Statement");
    }

    /**
     * Order-independent rendering of one statement, with CloudFormation intrinsics resolved to
     * the same placeholder tokens the reference copy carries. Sid and Effect are deliberately
     * excluded: the template has no Sids, and every statement is an Allow.
     */
    private static String canonical(Map<String, Object> statement) {
        return "actions=" + strings(statement.get("Action"))
            + " resources=" + strings(statement.get("Resource"))
            + " condition=" + condition(statement.get("Condition"));
    }

    @SuppressWarnings("unchecked")
    private static String condition(Object raw) {
        if (raw == null) {
            return "{}";
        }
        var normalised = new TreeMap<String, Map<String, Set<String>>>();
        ((Map<String, Object>) raw).forEach((operator, keys) -> {
            var byKey = new TreeMap<String, Set<String>>();
            ((Map<String, Object>) keys).forEach((key, value) -> byKey.put(key, strings(value)));
            normalised.put(operator, byKey);
        });
        return normalised.toString();
    }

    /** A policy field is either a single string or a list of them; both become a sorted set. */
    @SuppressWarnings("unchecked")
    private static Set<String> strings(Object raw) {
        if (raw == null) {
            return Set.of();
        }
        var values = raw instanceof List<?> list ? (List<Object>) list : List.of(raw);
        return values.stream()
            .map(value -> substitute(String.valueOf(value)))
            .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * {@link InfraFixtures} collapses {@code !Sub}/{@code !Ref}/{@code !GetAtt} to their
     * arguments, so what survives is the raw template expression. Resolve those to the tokens
     * {@code operator-policy.json} documents, so the two are comparable.
     */
    private static String substitute(String value) {
        return value
            .replace("${S3MainBucket.Arn}", "arn:aws:s3:::baas-RESOURCE_PREFIX")
            .replace("S3MainBucket.Arn", "arn:aws:s3:::baas-RESOURCE_PREFIX")
            .replace("RunnerRole.Arn", "arn:aws:iam::ACCOUNT_ID:role/RESOURCE_PREFIX-runner-role")
            .replace("${AWS::Partition}", "aws")
            .replace("${AWS::Region}", "REGION")
            .replace("${AWS::AccountId}", "ACCOUNT_ID")
            .replace("${ResourceNamePrefix}", "RESOURCE_PREFIX")
            .replace("AWS::Region", "REGION");
    }
}
