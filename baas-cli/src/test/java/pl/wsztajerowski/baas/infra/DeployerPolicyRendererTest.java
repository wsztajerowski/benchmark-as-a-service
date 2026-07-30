package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;

import static org.assertj.core.api.Assertions.assertThat;

class DeployerPolicyRendererTest {

    private final DeployerPolicyRenderer renderer = new DeployerPolicyRenderer();

    @Test
    void substitutesEveryPlaceholder() {
        String rendered = renderer.render("123456789012", "eu-central-1", "a1b2c3d4");

        assertThat(rendered)
            .as("an unresolved placeholder would be attached verbatim and match nothing")
            .doesNotContain("${");
    }

    @Test
    void namesTheCallersOwnResources() {
        String rendered = renderer.render("123456789012", "eu-central-1", "a1b2c3d4");

        assertThat(rendered)
            .contains("arn:aws:iam::123456789012:role/a1b2c3d4-runner-role")
            .contains("arn:aws:s3:::baas-a1b2c3d4")
            .contains("arn:aws:ssm:eu-central-1:123456789012:parameter/a1b2c3d4/mongo/connection-string");
    }

    @Test
    void accessDeniedIsRecognisedThroughWrappingExceptions() {
        var denied = AwsServiceException.builder()
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").build())
            .build();

        assertThat(DeployerPreflight.isAccessDenied(new RuntimeException("wrapped", denied)))
            .as("the SDK exception is usually buried under a CloudFormation or CLI-level failure")
            .isTrue();
        assertThat(DeployerPreflight.isAccessDenied(new RuntimeException("unrelated"))).isFalse();
    }
}
