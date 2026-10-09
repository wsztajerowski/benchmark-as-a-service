package pl.wsztajerowski.baas.infra;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.Optional;
import java.util.function.Function;

/**
 * Who holds a bucket name, as setup needs to know before it creates a stack that names its bucket
 * after the deployment. Bucket names are global, so the answer is one of three.
 *
 * <p>A live probe on 2026-10-09 is why a redirect is asked again: from a region other than the
 * bucket's, S3 answers 301 with {@code x-amz-bucket-region} for the caller's own bucket and for
 * another account's alike ({@code test} from eu-central-1). Only a request in the bucket's own
 * region tells them apart — 200 for the caller's, 403 for someone else's.
 */
public sealed interface BucketProbe {

    /** No bucket of that name exists anywhere. */
    record Absent() implements BucketProbe {}

    /** The caller can read the bucket, which lives in {@code region}. */
    record Reachable(String region) implements BucketProbe {}

    /** The bucket exists, in {@code region}, and the caller may not read it. */
    record Forbidden(String region) implements BucketProbe {}

    /**
     * @param home      the region asked first
     * @param clientFor an S3 client for a region; the caller closes nothing, every client is closed here
     */
    static BucketProbe of(String bucket, String home, Function<String, S3Client> clientFor) {
        BucketProbe first = ask(bucket, home, clientFor);
        if (first instanceof Reachable(String region) && !region.equals(home)) {
            // A redirect: ask the region it names, where a 403 can show itself.
            return ask(bucket, region, clientFor);
        }
        return first;
    }

    private static BucketProbe ask(String bucket, String region, Function<String, S3Client> clientFor) {
        try (S3Client s3 = clientFor.apply(region)) {
            String answered = s3.headBucket(r -> r.bucket(bucket)).bucketRegion();
            return new Reachable(answered != null ? answered : region);
        } catch (NoSuchBucketException e) {
            return new Absent();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return new Absent();
            }
            Optional<String> header = e.awsErrorDetails() == null || e.awsErrorDetails().sdkHttpResponse() == null
                ? Optional.empty()
                : e.awsErrorDetails().sdkHttpResponse().firstMatchingHeader("x-amz-bucket-region");
            if (e.statusCode() == 403) {
                return new Forbidden(header.orElse(region));
            }
            if (header.isPresent()) {
                // 301 or 400: the bucket is in another region. Whose it is, the caller learns there.
                return new Reachable(header.get());
            }
            throw e;
        }
    }
}
