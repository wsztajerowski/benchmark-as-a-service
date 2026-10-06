package pl.wsztajerowski.baas.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Config file, then AWS_REGION, then the default. CI's fresh config names no region, and used to get
 * eu-central-1 whatever the CI job's credentials step was told — right only while the two matched.
 */
class RegionResolutionTest {

    @Test
    void theFileWinsOverTheEnvironment() {
        var aws = new BaasConfig.AwsConfig();
        aws.setRegion("eu-west-1");

        assertThat(aws.resolveRegion(Map.of("AWS_REGION", "us-east-1"))).isEqualTo("eu-west-1");
    }

    @Test
    void aFileWithNoRegionFollowsTheEnvironment() {
        assertThat(new BaasConfig.AwsConfig().resolveRegion(Map.of("AWS_REGION", "us-east-1")))
            .isEqualTo("us-east-1");
    }

    @Test
    void neitherFallsBackToTheDefault() {
        assertThat(new BaasConfig.AwsConfig().resolveRegion(Map.of()))
            .isEqualTo(BaasConfig.AwsConfig.DEFAULT_REGION);
    }

    /** Otherwise one `config sync` in CI would pin that CI job's AWS_REGION into the file for good. */
    @Test
    void savingAConfigWithNoRegionWritesNone() throws Exception {
        var config = new BaasConfig();
        config.setPrefix("baas-123456789012");

        String written = new ObjectMapper(new YAMLFactory()).writeValueAsString(config);

        assertThat(written).doesNotContain("region");
    }
}
