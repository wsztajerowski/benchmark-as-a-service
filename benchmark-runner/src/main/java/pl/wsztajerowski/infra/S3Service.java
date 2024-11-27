package pl.wsztajerowski.infra;

import java.nio.file.Path;

public interface S3Service {
    void saveFileOnS3(String objectKey, Path pathToFile);
    String getEndpoint();
    String getBucketName();
}
