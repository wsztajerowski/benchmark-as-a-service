package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.config.DeploymentNames;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The longest deployment name {@code admin deployment setup} accepts is derived from the role
 * suffixes {@link DeploymentNames} knows. A role the template adds without it would raise the real
 * limit's floor silently: setup would accept a name the stack then fails to create, minutes in.
 */
class DeploymentNamesTemplateTest {

    private static final Pattern ROLE_NAME =
        Pattern.compile("\\$\\{ResourceNamePrefix}(-role-[a-z-]+)");

    @Test
    void everyRoleTheTemplateNamesIsOneDeploymentNamesKnows() throws IOException {
        Set<String> templateSuffixes = new TreeSet<>();
        Matcher matcher = ROLE_NAME.matcher(coreTemplateText());
        while (matcher.find()) {
            templateSuffixes.add(matcher.group(1));
        }

        assertThat(templateSuffixes)
            .isNotEmpty()
            .containsExactlyInAnyOrderElementsOf(DeploymentNames.ROLE_SUFFIXES);
    }

    private static String coreTemplateText() throws IOException {
        try (InputStream is = DeploymentNamesTemplateTest.class
            .getResourceAsStream("/templates/cf-template-core.yaml")) {
            assertThat(is).as("the core template is on the classpath").isNotNull();
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
