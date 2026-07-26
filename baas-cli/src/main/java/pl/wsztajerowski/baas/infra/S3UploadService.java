package pl.wsztajerowski.baas.infra;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class S3UploadService {

    private final S3Client s3;

    public S3UploadService(S3Client s3) {
        this.s3 = s3;
    }

    public void upload(Path localFile, String bucket, String key) {
        s3.putObject(PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .build(), RequestBody.fromFile(localFile));
    }

    public Optional<String> getObjectIfExists(String bucket, String key) {
        try {
            return Optional.of(s3.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build()).asUtf8String());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    /**
     * Removes every object version and delete marker, which is what a versioning-enabled
     * bucket needs before it can be deleted. Listing only current versions leaves
     * noncurrent ones behind and DeleteBucket then fails with BucketNotEmpty.
     *
     * <p>Deletes are batched — a bucket holding a month of runs can carry tens of
     * thousands of versions, and one request each would take minutes.
     */
    public void deleteAllObjects(String bucket) {
        List<String> failures = new ArrayList<>();
        s3.listObjectVersionsPaginator(r -> r.bucket(bucket)).stream().forEach(page -> {
            List<ObjectIdentifier> batch = new ArrayList<>();
            page.versions().forEach(version -> batch.add(ObjectIdentifier.builder()
                .key(version.key()).versionId(version.versionId()).build()));
            page.deleteMarkers().forEach(marker -> batch.add(ObjectIdentifier.builder()
                .key(marker.key()).versionId(marker.versionId()).build()));
            // DeleteObjects caps at 1000 keys per request.
            for (int start = 0; start < batch.size(); start += 1000) {
                var chunk = batch.subList(start, Math.min(start + 1000, batch.size()));
                var response = s3.deleteObjects(r -> r
                    .bucket(bucket)
                    .delete(d -> d.objects(chunk).quiet(true)));
                response.errors().forEach(error ->
                    failures.add(error.key() + " (" + error.code() + ": " + error.message() + ")"));
            }
        });

        if (!failures.isEmpty()) {
            throw new IllegalStateException("Failed to delete " + failures.size()
                + " object(s) from " + bucket + ": " + String.join(", ", failures.subList(0, Math.min(5, failures.size())))
                + (failures.size() > 5 ? ", ..." : ""));
        }
    }

    /**
     * The stack declares {@code DeletionPolicy: Retain} on the bucket, so CloudFormation
     * never removes it — teardown has to do it explicitly when asked.
     */
    public void deleteBucket(String bucket) {
        s3.deleteBucket(r -> r.bucket(bucket));
    }
}
