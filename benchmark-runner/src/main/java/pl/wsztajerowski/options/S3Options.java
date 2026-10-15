package pl.wsztajerowski.options;

import java.net.URI;

public record S3Options(String s3BucketName, URI s3ServiceEndpoint) {
}
