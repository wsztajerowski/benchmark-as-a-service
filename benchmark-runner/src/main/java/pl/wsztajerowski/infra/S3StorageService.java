package pl.wsztajerowski.infra;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.file.Path;

public class S3StorageService implements StorageService {
    private final String bucketName;
    private final S3Client s3Client;

    public S3StorageService(S3Client client, String bucketName) {
        s3Client = client;
        this.bucketName = bucketName;
    }

    @Override
    public void saveFile(Path storagePath, Path localPath) {
        PutObjectRequest putOb = PutObjectRequest.builder()
            .bucket(bucketName)
            .key(storagePath.toString())
            .build();

        s3Client.putObject(putOb, RequestBody.fromFile(localPath));
    }

    public String getEndpoint(){
        return s3Client
            .serviceClientConfiguration()
            .endpointOverride()
            .map(endpoint ->
                "https://%s.%s".formatted(bucketName, endpoint.getAuthority()))
            .orElse("https://%s.console.aws.amazon.com/s3/buckets/%s"
                .formatted(s3Client.serviceClientConfiguration().region(), bucketName));
    }

}
