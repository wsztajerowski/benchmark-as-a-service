package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The answers below are the ones a live probe returned on 2026-10-09 (pr83-review-fixes task 1.1):
 * the account's own bucket answers 200 from its region and 301 elsewhere; {@code test}, another
 * account's, answered 301 from eu-central-1 and 403 from its own us-west-2; a free name 404.
 */
class BucketProbeTest {

    private final List<String> asked = new ArrayList<>();

    private Function<String, S3Client> regions(Map<String, Supplier<HeadBucketResponse>> answers) {
        return region -> new S3Client() {
            @Override
            public HeadBucketResponse headBucket(HeadBucketRequest request) {
                asked.add(region);
                return answers.get(region).get();
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

    private static Supplier<HeadBucketResponse> refused(int status, String regionHeader) {
        return () -> {
            var http = SdkHttpResponse.builder().statusCode(status);
            if (regionHeader != null) {
                http.putHeader("x-amz-bucket-region", regionHeader);
            }
            throw (S3Exception) S3Exception.builder().statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder().sdkHttpResponse(http.build()).build())
                .build();
        };
    }

    private static Supplier<HeadBucketResponse> found(String region) {
        return () -> HeadBucketResponse.builder().bucketRegion(region).build();
    }

    @Test
    void theCallersBucketInItsRegionIsReachableThere() {
        var probe = BucketProbe.of("b", "eu-central-1", regions(Map.of("eu-central-1", found("eu-central-1"))));

        assertThat(probe).isEqualTo(new BucketProbe.Reachable("eu-central-1"));
    }

    @Test
    void aRedirectIsAskedAgainWhereItPoints() {
        var probe = BucketProbe.of("b", "us-east-1", regions(Map.of(
            "us-east-1", refused(301, "eu-central-1"),
            "eu-central-1", found("eu-central-1"))));

        assertThat(probe).isEqualTo(new BucketProbe.Reachable("eu-central-1"));
        assertThat(asked).containsExactly("us-east-1", "eu-central-1");
    }

    /** The case the redirect hid: from eu-central-1, another account's bucket looked like a 301. */
    @Test
    void anotherAccountsBucketElsewhereIsForbiddenNotReachable() {
        var probe = BucketProbe.of("test", "eu-central-1", regions(Map.of(
            "eu-central-1", refused(301, "us-west-2"),
            "us-west-2", refused(403, "us-west-2"))));

        assertThat(probe).isEqualTo(new BucketProbe.Forbidden("us-west-2"));
    }

    @Test
    void anotherAccountsBucketInTheSameRegionIsForbidden() {
        var probe = BucketProbe.of("b", "eu-central-1", regions(Map.of("eu-central-1", refused(403, "eu-central-1"))));

        assertThat(probe).isEqualTo(new BucketProbe.Forbidden("eu-central-1"));
    }

    @Test
    void aFreeNameIsAbsent() {
        var probe = BucketProbe.of("b", "eu-central-1", regions(Map.of("eu-central-1", refused(404, null))));

        assertThat(probe).isEqualTo(new BucketProbe.Absent());
    }

    @Test
    void anUnexplainedFailureIsNotTakenForAnAnswer() {
        var noAnswer = regions(Map.of("eu-central-1", refused(500, null)));

        assertThatThrownBy(() -> BucketProbe.of("b", "eu-central-1", noAnswer)).isInstanceOf(S3Exception.class);
    }
}
