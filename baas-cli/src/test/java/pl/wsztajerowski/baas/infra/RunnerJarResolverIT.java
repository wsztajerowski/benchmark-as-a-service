package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seed's refusal to overwrite is enforced by S3, not by the order of a head and a put, so it
 * holds for two first jobs of a version that both found the slot empty.
 */
@Testcontainers(disabledWithoutDocker = true)
class RunnerJarResolverIT {

    private static final String KEY = "releases/7.1.0/benchmark-runner.jar";

    @Container
    private static final LocalStackContainer LOCAL_STACK =
        new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.14.0"))
            .withServices(LocalStackContainer.Service.S3);

    private S3Client s3;
    private String bucket;

    @BeforeEach
    void createBucket() {
        s3 = S3Client.builder()
            .endpointOverride(LOCAL_STACK.getEndpoint())
            .region(Region.of(LOCAL_STACK.getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(LOCAL_STACK.getAccessKey(), LOCAL_STACK.getSecretKey())))
            .forcePathStyle(true)
            .build();
        bucket = "baas-test-" + UUID.randomUUID();
        s3.createBucket(r -> r.bucket(bucket));
    }

    @Test
    void theFirstSeedWritesTheSlot() {
        assertThat(RunnerJarResolver.seed(s3, bucket, KEY, bytes("first"))).isTrue();

        assertThat(RunnerJarResolver.exists(s3, bucket, KEY)).isTrue();
    }

    @Test
    void aSecondSeedIsRefusedAndLeavesTheFirstObjectUnchanged() {
        RunnerJarResolver.seed(s3, bucket, KEY, bytes("first"));

        assertThat(RunnerJarResolver.seed(s3, bucket, KEY, bytes("second"))).isFalse();

        assertThat(s3.getObjectAsBytes(r -> r.bucket(bucket).key(KEY)).asUtf8String()).isEqualTo("first");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
