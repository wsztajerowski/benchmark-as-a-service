package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The responses below are the ones a live probe returned on 2026-10-04 for the account's bucket in
 * eu-central-1: from eu-central-1 a 200 with the region, from us-east-1 a 400 and from us-west-2 a
 * 301, both carrying {@code x-amz-bucket-region}; a missing bucket a 404 with no header.
 */
class S3BucketRegionTest {

    private static S3Client answering(Supplier<HeadBucketResponse> answer) {
        return new S3Client() {
            @Override
            public HeadBucketResponse headBucket(HeadBucketRequest request) {
                return answer.get();
            }

            @Override
            public String serviceName() {
                return "s3";
            }

            @Override
            public void close() {
            }
        };
    }

    private static S3Exception refused(int status, String regionHeader) {
        var http = SdkHttpResponse.builder().statusCode(status);
        if (regionHeader != null) {
            http.putHeader("x-amz-bucket-region", regionHeader);
        }
        return (S3Exception) S3Exception.builder().statusCode(status)
            .awsErrorDetails(AwsErrorDetails.builder().sdkHttpResponse(http.build()).build())
            .build();
    }

    @Test
    void aBucketInTheClientsRegionReportsIt() {
        var s3 = answering(() -> HeadBucketResponse.builder().bucketRegion("eu-central-1").build());

        assertThat(new S3UploadService(s3).bucketRegion("b")).contains("eu-central-1");
    }

    @Test
    void aBucketInAnotherRegionIsFoundThroughTheRefusalsHeader() {
        assertThat(new S3UploadService(answering(() -> { throw refused(301, "eu-central-1"); }))
            .bucketRegion("b")).contains("eu-central-1");
        assertThat(new S3UploadService(answering(() -> { throw refused(400, "eu-central-1"); }))
            .bucketRegion("b")).contains("eu-central-1");
        assertThat(new S3UploadService(answering(() -> { throw refused(403, "eu-central-1"); }))
            .bucketRegion("b")).as("a bucket the caller cannot read still exists").contains("eu-central-1");
    }

    @Test
    void noBucketIsEmpty() {
        assertThat(new S3UploadService(answering(() -> { throw refused(404, null); })).bucketRegion("b")).isEmpty();
    }

    /** Without a region a denial says nothing about absence, so it must not read as "no bucket". */
    @Test
    void aDenialWithoutARegionIsNotTakenForAbsence() {
        var s3 = answering(() -> { throw refused(403, null); });

        assertThatThrownBy(() -> new S3UploadService(s3).bucketRegion("b")).isInstanceOf(S3Exception.class);
    }
}
