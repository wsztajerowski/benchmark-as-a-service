package pl.wsztajerowski.baas.infra;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.file.Path;
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

    public void deleteAllObjects(String bucket) {
        var paginator = s3.listObjectsV2Paginator(r -> r.bucket(bucket));
        paginator.contents().forEach(obj ->
            s3.deleteObject(r -> r.bucket(bucket).key(obj.key())));
    }
}
