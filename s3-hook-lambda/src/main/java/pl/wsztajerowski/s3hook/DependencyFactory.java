
package pl.wsztajerowski.s3hook;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.net.URI;

public class DependencyFactory {
    public static final String CUSTOM_ENDPOINT = System.getenv("AWS_CUSTOM_ENDPOINT");

    private DependencyFactory() {
    }

    public static S3Client s3Client() {
        return S3Client.builder()
            .forcePathStyle(true)
            .endpointOverride(CUSTOM_ENDPOINT != null ? URI.create(CUSTOM_ENDPOINT) : null)
            .build();
    }

    public static SsmClient ssmClient() {
        return SsmClient.builder()
            .endpointOverride(CUSTOM_ENDPOINT != null ? URI.create(CUSTOM_ENDPOINT) : null)
            .build();
    }

}
