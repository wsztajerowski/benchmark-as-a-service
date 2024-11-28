package pl.wsztajerowski.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.services.options.S3Options;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.util.Optional;

public class StorageServiceBuilder {
    private static final Logger logger = LoggerFactory.getLogger(StorageServiceBuilder.class);
    private String bucketName;
    private URI s3ServiceEndpoint;

    private StorageServiceBuilder(){
    }

    public static StorageServiceBuilder getS3ServiceBuilder(){
        return new StorageServiceBuilder();
    }

    public StorageServiceBuilder withS3Options(S3Options s3Options) {
        this.bucketName = s3Options.s3BucketName();
        s3ServiceEndpoint = s3Options.s3ServiceEndpoint();
        return this;
    }

    public StorageService build(){
        if (bucketName == null || bucketName.isBlank()) {
            logger.info("Using Local storage service");
            return new LocalStorageService();
        } else {
            S3Client client = Optional.ofNullable(s3ServiceEndpoint)
                .map(uri -> S3Client.builder().endpointOverride(uri).build())
                .orElseGet(() -> S3Client.builder().build());
            S3StorageService s3OperationalService = new S3StorageService(client, bucketName);
            logger.info("Using S3 service with endpoint: {}", s3OperationalService.getEndpoint());
            return s3OperationalService;
        }
    }
}
