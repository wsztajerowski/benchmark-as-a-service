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
            .contains("arn:aws:dynamodb:eu-central-1:123456789012:table/baas-a1b2c3d4-results")
            .contains("arn:aws:ssm:eu-central-1:123456789012:parameter/a1b2c3d4/runner/ami-id");
    }

    /**
     * The deployer held PutParameter/DeleteParameter on the mongo connection string so
     * {@code baas admin setup --mongo-uri} could write it. Both the option and the parameter are
     * gone; a grant on a parameter nothing writes is standing reach for no reason.
     */
    @Test
    void noLongerNamesTheMongoConnectionString() {
        String rendered = renderer.render("123456789012", "eu-central-1", "a1b2c3d4");

        assertThat(rendered).doesNotContain("mongo");
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
