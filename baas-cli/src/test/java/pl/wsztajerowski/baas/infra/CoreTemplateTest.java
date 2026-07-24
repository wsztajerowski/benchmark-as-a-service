package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CoreTemplateTest {

    private final Map<String, Object> template = InfraFixtures.coreTemplate();

    @Test
    void bucketIsNamedFromTheResourcePrefix() {
        Map<String, Object> bucket = InfraFixtures.properties(template, "S3MainBucket");

        assertThat(bucket.get("BucketName")).isEqualTo("baas-${ResourceNamePrefix}");
    }
}
