package pl.wsztajerowski.infra;

import software.amazon.awssdk.services.s3.S3Client;
import pl.wsztajerowski.options.S3Options;

import java.util.Optional;
import java.nio.file.Path;

public interface StorageService {
    void saveFile(Path storagePath, Path localPath);

    /**
     * The job's bucket. There is no local-disk mode: {@code --s3-bucket} is required, so a runner
     * cannot quietly keep a job's artifacts on an instance about to terminate.
     */
    static StorageService of(S3Options s3Options) {
        String bucketName = s3Options.s3BucketName();
        if (bucketName == null || bucketName.isBlank()) {
            throw new IllegalStateException("No artifact bucket configured. Pass --s3-bucket.");
        }
        S3Client client = Optional.ofNullable(s3Options.s3ServiceEndpoint())
            .map(uri -> S3Client.builder().endpointOverride(uri).build())
            .orElseGet(() -> S3Client.builder().build());
        S3StorageService s3OperationalService = new S3StorageService(client, bucketName);
        LOGGER.info("Using S3 service with endpoint: {}", s3OperationalService.getEndpoint());
        return s3OperationalService;
    }

    /** Named after the builder it replaced, so the two log lines keep the prefix they always had. */
    org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("pl.wsztajerowski.infra.StorageServiceBuilder");
}
