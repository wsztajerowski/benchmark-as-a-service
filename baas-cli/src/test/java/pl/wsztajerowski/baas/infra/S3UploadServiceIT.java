package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class S3UploadServiceIT {

    @Container
    private static final LocalStackContainer LOCAL_STACK =
        new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8"))
            .withServices(LocalStackContainer.Service.S3);

    private S3Client s3;
    private String bucket;

    @BeforeEach
    void createVersionedBucket() {
        s3 = S3Client.builder()
            .endpointOverride(LOCAL_STACK.getEndpoint())
            .region(Region.of(LOCAL_STACK.getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(LOCAL_STACK.getAccessKey(), LOCAL_STACK.getSecretKey())))
            .forcePathStyle(true)
            .build();

        bucket = "baas-test-" + UUID.randomUUID();
        s3.createBucket(r -> r.bucket(bucket));
        s3.putBucketVersioning(r -> r.bucket(bucket)
            .versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)));
    }

    @Test
    void emptiesAVersionedBucketCompletely() {
        // Three versions of one key, plus a delete marker on a second key.
        for (int i = 0; i < 3; i++) {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key("runs/benchmark.jar").build(),
                RequestBody.fromString("payload-" + i));
        }
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key("runs/result.json").build(),
            RequestBody.fromString("{}"));
        s3.deleteObject(r -> r.bucket(bucket).key("runs/result.json"));

        new S3UploadService(s3).deleteAllObjects(bucket);

        var remaining = s3.listObjectVersions(r -> r.bucket(bucket));
        assertThat(remaining.versions()).isEmpty();
        assertThat(remaining.deleteMarkers()).isEmpty();
    }

    @Test
    void deletingAnEmptyBucketIsANoOp() {
        new S3UploadService(s3).deleteAllObjects(bucket);

        assertThat(s3.listObjectVersions(r -> r.bucket(bucket)).versions()).isEmpty();
    }
}
