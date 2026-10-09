package pl.wsztajerowski.baas.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class S3UploadService {

    private static final Logger logger = LoggerFactory.getLogger(S3UploadService.class);

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

    public void putText(String bucket, String key, String content) {
        s3.putObject(PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType("text/plain; charset=utf-8")
            .build(), RequestBody.fromString(content));
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
     * Every key under a prefix, paginated. Used to enumerate a job's artifacts before downloading
     * them, so an empty list is how "no such job" is detected — S3 has no directories to miss.
     */
    public List<String> listKeys(String bucket, String prefix) {
        List<String> keys = new ArrayList<>();
        var request = ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build();
        for (var page : s3.listObjectsV2Paginator(request)) {
            page.contents().forEach(object -> keys.add(object.key()));
        }
        return keys;
    }

    /** Creates parent directories as needed; overwrites an existing file. */
    public void download(String bucket, String key, Path destination) {
        try {
            Files.createDirectories(destination.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Could not create " + destination.getParent(), e);
        }
        s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(), destination);
    }

    /**
     * Removes every object version and delete marker, which is what a versioning-enabled
     * bucket needs before it can be deleted. Listing only current versions leaves
     * noncurrent ones behind and DeleteBucket then fails with BucketNotEmpty.
     *
     * <p>Deletes are batched — a bucket holding a month of jobs can carry tens of
     * thousands of versions, and one request each would take minutes.
     */
    public void deleteAllObjects(String bucket) {
        List<String> failures = new ArrayList<>();
        try {
            deleteEveryVersion(bucket, failures);
        } catch (NoSuchBucketException e) {
            // Already empty: a bucket deleted by hand, or a stack whose create rolled back before
            // making it. Teardown carries on to the stack, which otherwise no re-run could reach.
            logger.info("Bucket {} does not exist; nothing to empty.", bucket);
            return;
        }

        if (!failures.isEmpty()) {
            throw new IllegalStateException("Failed to delete " + failures.size()
                + " object(s) from " + bucket + ": " + String.join(", ", failures.subList(0, Math.min(5, failures.size())))
                + (failures.size() > 5 ? ", ..." : ""));
        }
    }

    private void deleteEveryVersion(String bucket, List<String> failures) {
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
    }

    /**
     * The stack declares {@code DeletionPolicy: Retain} on the bucket, so CloudFormation
     * never removes it — teardown has to do it explicitly when asked.
     */
    public void deleteBucket(String bucket) {
        s3.deleteBucket(r -> r.bucket(bucket));
    }

    /**
     * Bucket names are global, so a retained bucket blocks a stack that wants to recreate it.
     * A 403 counts as existing: the name is taken either way, which is all the caller needs
     * to know, and treating it as absent would send them into a create that cannot succeed.
     */
    /**
     * The region a bucket lives in, or empty when no bucket has that name. Bucket names are global,
     * so this answers from a client in any region: a wrong-region request is refused with 301 or
     * 400 and still carries {@code x-amz-bucket-region} (checked live, 2026-10-04), and so does a
     * 403 for a bucket the caller cannot read. Needs only {@code s3:ListBucket}, which HeadBucket
     * is authorised against.
     */
    public Optional<String> bucketRegion(String bucket) {
        try {
            return Optional.ofNullable(s3.headBucket(r -> r.bucket(bucket)).bucketRegion());
        } catch (NoSuchBucketException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            Optional<String> header = e.awsErrorDetails() == null || e.awsErrorDetails().sdkHttpResponse() == null
                ? Optional.empty()
                : e.awsErrorDetails().sdkHttpResponse().firstMatchingHeader("x-amz-bucket-region");
            if (header.isPresent()) {
                return header;
            }
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }
}
