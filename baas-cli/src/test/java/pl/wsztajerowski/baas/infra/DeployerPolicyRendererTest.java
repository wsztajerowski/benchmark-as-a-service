package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;

import static org.assertj.core.api.Assertions.assertThat;

class DeployerPolicyRendererTest {

    private final DeployerPolicyRenderer renderer = new DeployerPolicyRenderer();

    @Test
    void substitutesEveryPlaceholder() {
        String rendered = renderer.render("123456789012", "eu-central-1", "baas-123456789012");

        assertThat(rendered)
            .as("an unresolved placeholder would be attached verbatim and match nothing")
            .doesNotContain("${");
    }

    @Test
    void namesTheCallersOwnResources() {
        String rendered = renderer.render("123456789012", "eu-central-1", "baas-123456789012");

        assertThat(rendered)
            .contains("arn:aws:iam::123456789012:role/baas-123456789012-role-runner")
            .contains("arn:aws:s3:::baas-123456789012")
            .contains("arn:aws:dynamodb:eu-central-1:123456789012:table/baas-123456789012-results")
            .contains("arn:aws:ssm:eu-central-1:123456789012:parameter/baas-123456789012/runner/ami-id");
    }

    /**
     * The deployer held PutParameter/DeleteParameter on the mongo connection string so
     * {@code baas admin setup --mongo-uri} could write it. Both the option and the parameter are
     * gone; a grant on a parameter nothing writes is standing reach for no reason.
     */
    @Test
    void noLongerNamesTheMongoConnectionString() {
        String rendered = renderer.render("123456789012", "eu-central-1", "baas-123456789012");

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

    /**
     * The policy must name exactly what {@code baas admin setup} asks AWS for. They drifted once:
     * setup's retained-resource pre-check composed {@code "baas-" + prefix} a second time and
     * probed {@code baas-baas-<account>-results}, which the policy did not grant and no unit test
     * covered, so it surfaced only as an AccessDenied against a live account.
     */
    @Test
    void grantsExactlyTheNamesTheConfigDerives() {
        var config = new pl.wsztajerowski.baas.config.BaasConfig();
        config.setPrefix("baas-123456789012");

        String rendered = renderer.render("123456789012", "eu-central-1", config.getPrefix());

        assertThat(rendered)
            .contains("arn:aws:s3:::" + config.bucket() + "\"")
            .contains("table/" + config.resultsTable() + "\"")
            .contains("stack/" + config.stackName() + "/*")
            .contains("parameter" + config.amiParameterPath())
            .contains("instance-profile/" + config.runnerInstanceProfile());
    }
}
