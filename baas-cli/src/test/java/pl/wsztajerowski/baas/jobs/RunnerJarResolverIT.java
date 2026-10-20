package pl.wsztajerowski.baas.jobs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import pl.wsztajerowski.baas.aws.Checksums;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The seed's refusal to overwrite is enforced by S3, not by the order of a head and a put, so it
 * holds for two first jobs of a version that both found the slot empty. And the per-launch check
 * reads the SHA-256 S3 itself computed at the seed (review X1).
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
    void theFirstSeedWritesTheSlotWithItsSha256() {
        assertThat(RunnerJarResolver.seed(s3, bucket, KEY, bytes("first"))).isTrue();

        assertThat(RunnerJarResolver.head(s3, bucket, KEY))
            .isEqualTo(new RunnerJarResolver.Stored(Checksums.sha256Hex(bytes("first"))));
    }

    @Test
    void anEmptySlotReadsAsNoObject() {
        assertThat(RunnerJarResolver.head(s3, bucket, KEY)).isNull();
    }

    /** No GitHub here: a seeded slot is checked from S3 alone, so the bogus repository is never reached. */
    @Test
    void aLaunchOnTheCliOwnRunnerUsesTheSlotWithoutFetchingAnything() {
        RunnerJarResolver.seed(s3, bucket, KEY, bytes("first"));

        assertThat(RunnerJarResolver.resolve(s3, bucket, "7.1.0", "nobody/nothing", Checksums.sha256Hex(bytes("first"))))
            .isEqualTo(KEY);
    }

    @Test
    void aRunnerReplacedInTheBucketIsRefused() {
        s3.putObject(r -> r.bucket(bucket).key(KEY).checksumAlgorithm(ChecksumAlgorithm.SHA256),
            RequestBody.fromBytes(bytes("replaced")));

        assertThatThrownBy(() -> RunnerJarResolver.resolve(s3, bucket, "7.1.0", "nobody/nothing",
                Checksums.sha256Hex(bytes("first"))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("is not the runner this CLI was released with")
            .hasMessageContaining(Checksums.sha256Hex(bytes("replaced")))
            .hasMessageContaining("aws s3 rm s3://" + bucket + "/" + KEY)
            .hasMessageContaining("Nothing was launched");
    }

    /** Every slot seeded before the check carries no SHA-256: refused, and the message says why. */
    @Test
    void aSlotWithNoSha256IsRefused() {
        s3.putObject(r -> r.bucket(bucket).key(KEY), RequestBody.fromBytes(bytes("first")));
        assertThat(RunnerJarResolver.head(s3, bucket, KEY)).isEqualTo(new RunnerJarResolver.Stored(null));

        assertThatThrownBy(() -> RunnerJarResolver.resolve(s3, bucket, "7.1.0", "nobody/nothing",
                Checksums.sha256Hex(bytes("first"))))
            .hasMessageContaining("no SHA-256 checksum");
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
