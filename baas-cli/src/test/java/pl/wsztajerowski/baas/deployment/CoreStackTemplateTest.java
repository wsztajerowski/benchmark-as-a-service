package pl.wsztajerowski.baas.deployment;

import pl.wsztajerowski.baas.testsupport.InfraFixtures;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.session.CoreStack;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Every stack output and parameter the CLI names through {@link CoreStack} is declared by the template. */
class CoreStackTemplateTest {

    @Test
    @SuppressWarnings("unchecked")
    void everyOutputTheCliReadsIsDeclared() {
        var outputs = (Map<String, Object>) InfraFixtures.coreTemplate().get("Outputs");
        assertThat(outputs).containsKeys(CoreStack.OUTPUT_SUBNET_ID, CoreStack.OUTPUT_SECURITY_GROUP_ID,
            CoreStack.OUTPUT_OPERATOR_ROLE_ARN, CoreStack.OUTPUT_RUNNER_IMAGE_PIPELINE_ARN);
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyParameterTheCliSetsIsDeclared() {
        var parameters = (Map<String, Object>) InfraFixtures.coreTemplate().get("Parameters");
        assertThat(parameters).containsKeys(List.of(CoreStack.PARAM_RESOURCE_NAME_PREFIX,
            CoreStack.PARAM_GITHUB_ORG, CoreStack.PARAM_GITHUB_REPO).toArray(String[]::new));
    }
}
