package pl.symentis.infra;

import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

import static java.util.Objects.requireNonNull;

public class S3ServiceBuilder {
    private S3Client s3Client;
    private String bucketName;

    private S3ServiceBuilder(){
    }

    public static S3ServiceBuilder getS3ServiceBuilder(){
        return new S3ServiceBuilder();
    }

    public static S3ServiceBuilder getDefaultS3ServiceBuilder(URI customEndpoint){
        S3Client client = S3Client
            .builder()
            .endpointOverride(customEndpoint)
            .build();
        return new S3ServiceBuilder()
            .withS3Client(client);
    }

    public S3ServiceBuilder withS3Client(S3Client client){
        this.s3Client = client;
        return this;
    }

    public S3ServiceBuilder withBucketName(String bucketName){
        this.bucketName = bucketName;
        return this;
    }

    public S3Service build(){
        requireNonNull(s3Client, "Please either provide a S3 client or invoke getDefaultS3ServiceBuilder method before");
        requireNonNull(bucketName, "Please provide AWS S3 bucket name");
        return new S3Service(s3Client, bucketName);
    }
}
