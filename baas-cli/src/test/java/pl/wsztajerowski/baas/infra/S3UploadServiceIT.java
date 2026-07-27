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

    /**
     * The stack declares DeletionPolicy: Retain, so CloudFormation never removes the bucket.
     * If teardown does not remove it explicitly, a retained bucket blocks the next
     * `baas admin setup` — the bucket name is a deterministic hash of the caller ARN.
     */
    @Test
    void removesTheBucketItselfNotJustItsContents() {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key("runs/result.json").build(),
            RequestBody.fromString("{}"));

        var service = new S3UploadService(s3);
        service.deleteAllObjects(bucket);
        service.deleteBucket(bucket);

        assertThat(s3.listBuckets().buckets())
            .extracting(software.amazon.awssdk.services.s3.model.Bucket::name)
            .doesNotContain(bucket);
    }

    /**
     * A retained bucket blocks the stack that wants to recreate it, and CloudFormation reports
     * that as "Validation failed with 1 error(s)" without ever naming S3. Setup pre-checks for
     * it, so this has to distinguish present from absent correctly.
     */
    @Test
    void detectsWhetherABucketNameIsTaken() {
        var service = new S3UploadService(s3);

        assertThat(service.bucketExists(bucket)).isTrue();
        assertThat(service.bucketExists("baas-definitely-not-created-" + UUID.randomUUID())).isFalse();

        service.deleteBucket(bucket);
        assertThat(service.bucketExists(bucket)).isFalse();
    }

    @Test
    void batchesDeletesAcrossMoreThanOnePage() {
        // listObjectVersions pages at 1000 keys; this crosses that boundary.
        for (int i = 0; i < 1005; i++) {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key("runs/obj-" + i).build(),
                RequestBody.fromString("x"));
        }

        new S3UploadService(s3).deleteAllObjects(bucket);

        var remaining = s3.listObjectVersions(r -> r.bucket(bucket));
        assertThat(remaining.versions()).isEmpty();
        assertThat(remaining.deleteMarkers()).isEmpty();
    }
}
