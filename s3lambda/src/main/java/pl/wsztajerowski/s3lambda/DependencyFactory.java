
package pl.wsztajerowski.s3lambda;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.transfer.s3.S3TransferManager;

import static software.amazon.awssdk.transfer.s3.SizeConstant.MB;
import java.net.URI;

/**
 * The module containing all dependencies required by the {@link App}.
 */
public class DependencyFactory {

//    public static final String S3_ENDPOINT = "http://localhost.localstack.cloud:4566";
    private DependencyFactory() {}

    public static S3AsyncClient s3AsyncClient() {
        AwsBasicCredentials credentials = AwsBasicCredentials.builder().accessKeyId("foo").secretAccessKey("bar").build();
        return S3AsyncClient.crtBuilder()
            .credentialsProvider(StaticCredentialsProvider.create(credentials))
            .forcePathStyle(true)
            .region(Region.EU_CENTRAL_1)
            .endpointOverride(URI.create("http://docker.for.mac.localhost:4566"))
            .targetThroughputInGbps(20.0)
            .minimumPartSizeInBytes(8 * MB)
            .build();
    }

    public static S3Client s3Client() {
        AwsBasicCredentials credentials = AwsBasicCredentials.builder().accessKeyId("foo").secretAccessKey("bar").build();
        return S3Client.builder()
            .credentialsProvider(StaticCredentialsProvider.create(credentials))
            .forcePathStyle(true)
//                       .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
                       .region(Region.EU_CENTRAL_1)
//                       .endpointOverride(URI.create(S3_ENDPOINT))
                       .endpointOverride(URI.create("http://docker.for.mac.localhost:4566"))
                       .build();
    }

    public static S3TransferManager s3TransferManager() {
        return S3TransferManager.builder()
            .s3Client(DependencyFactory.s3AsyncClient())
            .build();
    }
}
