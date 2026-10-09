package pl.wsztajerowski.baas.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentNameRuleTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "baas-123456789012",
        "wiktor-dev",
        "abc",
        "a1b",
        "dev-2026-10",
        // The user's call: a name that looks like another account's default is not refused.
        "baas-999999999999",
    })
    void acceptedNames(String name) {
        assertThat(DeploymentNameRule.violation(name)).isEmpty();
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
        "ab               | at least 3",
        "Wiktor-dev       | lowercase",
        "1dev             | start with a lowercase letter",
        "-dev             | start with a lowercase letter",
        "dev-             | end with a letter or digit",
        "wiktor_dev       | only lowercase letters",
        "wiktor.dev       | only lowercase letters",
        "wiktor--dev      | \"--\"",
        "xn--dev          | \"--\"",
        "aws-dev          | \"aws\" (reserved by SSM)",
        "awsome           | \"aws\" (reserved by SSM)",
        "ssm-dev          | \"ssm\" (reserved by SSM)",
        "sthree-dev       | \"sthree-\" (reserved by S3)",
        "amzn-s3-demo-dev | \"amzn-s3-demo-\" (reserved by S3)",
        "dev-s3alias      | \"-s3alias\" (reserved by S3)",
    })
    void refusedNamesNameTheirRule(String name, String rule) {
        assertThat(DeploymentNameRule.violation(name)).hasValueSatisfying(v -> assertThat(v).contains(rule));
    }

    @Test
    void theLengthBoundaryIsTheLongestRoleName() {
        String atLimit = "a".repeat(DeploymentNames.MAX_PREFIX_LENGTH);
        String overLimit = atLimit + "a";

        assertThat(DeploymentNameRule.violation(atLimit)).isEmpty();
        assertThat(DeploymentNameRule.violation(overLimit))
            .hasValueSatisfying(v -> assertThat(v)
                .contains("at most 47")
                .contains(overLimit + "-role-image-build"));
    }
}
