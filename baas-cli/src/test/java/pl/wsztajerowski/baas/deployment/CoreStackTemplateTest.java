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
            CoreStack.PARAM_GITHUB_ORG, CoreStack.PARAM_GITHUB_REPO, CoreStack.PARAM_BAAS_CLI_VERSION)
            .toArray(String[]::new));
    }

    /**
     * CloudFormation answers "No updates are to be performed" to a change of a parameter nothing
     * references, and stores nothing (cli-version-floor, verify.md 1.1): without this output, setup
     * could never record a newer version on an unchanged template.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theRecordedCliVersionIsReferencedSoThatAChangeIsAnUpdate() {
        var outputs = (Map<String, Object>) InfraFixtures.coreTemplate().get("Outputs");
        var output = (Map<String, Object>) outputs.get(CoreStack.PARAM_BAAS_CLI_VERSION);

        assertThat(output).containsEntry("Condition", "RecordsCliVersion");
        assertThat(String.valueOf(output.get("Value"))).contains(CoreStack.PARAM_BAAS_CLI_VERSION);
    }

    /** Every recorded value is comparable, and empty records nothing. */
    @Test
    @SuppressWarnings("unchecked")
    void theRecordedCliVersionIsEmptyOrNumeric() {
        var parameters = (Map<String, Object>) InfraFixtures.coreTemplate().get("Parameters");
        var parameter = (Map<String, Object>) parameters.get(CoreStack.PARAM_BAAS_CLI_VERSION);

        assertThat(parameter).containsEntry("Default", "");
        var pattern = java.util.regex.Pattern.compile(String.valueOf(parameter.get("AllowedPattern")));
        assertThat(pattern.matcher("").matches()).isTrue();
        assertThat(pattern.matcher("8.10.2").matches()).isTrue();
        assertThat(pattern.matcher("8.0.0-rc.1").matches()).isFalse();
        assertThat(pattern.matcher("0.0.0-semantically-released").matches()).isFalse();
    }
}
