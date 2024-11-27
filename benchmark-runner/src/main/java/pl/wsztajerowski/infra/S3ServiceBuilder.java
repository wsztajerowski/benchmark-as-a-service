package pl.wsztajerowski.infra;

import pl.wsztajerowski.services.options.S3Options;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

public class S3ServiceBuilder {
    private S3Client s3Client;
    private String bucketName;

    private S3ServiceBuilder(){
    }

    public static S3ServiceBuilder getS3ServiceBuilder(){
        return new S3ServiceBuilder();
    }

    public S3ServiceBuilder withS3Client(S3Client client){
        this.s3Client = client;
        return this;
    }

    public S3ServiceBuilder withBucketName(String bucketName){
        this.bucketName = bucketName;
        return this;
    }

    public S3ServiceBuilder withS3Options(S3Options s3Options) {
        withBucketName(s3Options.s3BucketName());
        S3Client client = Optional.ofNullable(s3Options.s3ServiceEndpoint())
            .map(uri -> S3Client.builder().endpointOverride(uri).build())
            .orElseGet(() -> S3Client.builder().build());
        withS3Client(client);
        return this;
    }

    public S3Service build(){
        requireNonNull(s3Client, "Please either provide a S3 client or invoke getDefaultS3ServiceBuilder method before");
        requireNonNull(bucketName, "Please provide AWS S3 bucket name");
        return new S3OperationalService(s3Client, bucketName);
    }
}
