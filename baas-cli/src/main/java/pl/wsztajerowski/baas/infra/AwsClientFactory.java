package pl.wsztajerowski.baas.infra;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.imagebuilder.ImagebuilderClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.sts.StsClient;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Every AWS client a command builds, in one region and under one set of credentials.
 *
 * <p>Both may be deferred: an operator command's factory resolves its credentials (identifying the
 * caller, assuming the operator role) and its region only when the first client is built. A command
 * that fails before touching AWS — or a test that stands its AWS-facing step in — therefore makes no
 * call at all, and a failure to resolve surfaces at the first client, before any request is sent.
 */
public class AwsClientFactory {

    private final Supplier<String> region;
    private final Supplier<AwsCredentialsProvider> credentials;

    public AwsClientFactory(String region, AwsCredentialsProvider credentials) {
        this(() -> region, () -> credentials);
    }

    private AwsClientFactory(Supplier<String> region, Supplier<AwsCredentialsProvider> credentials) {
        this.region = memoize(region);
        this.credentials = memoize(credentials);
    }

    /** A factory whose region and credentials are resolved when the first client is built, once. */
    public static AwsClientFactory deferred(Supplier<String> region, Supplier<AwsCredentialsProvider> credentials) {
        return new AwsClientFactory(region, credentials);
    }

    /**
     * The named profile's credentials, or with none the default credential chain — so
     * {@code AWS_PROFILE} and ambient credentials (CI's federated session) still work.
     */
    public static AwsCredentialsProvider profile(String name) {
        return name != null && !name.isBlank()
            ? ProfileCredentialsProvider.create(name.strip())
            : DefaultCredentialsProvider.builder().build();
    }

    /** The region every client addresses. Resolves a deferred factory. */
    public String region() {
        return region.get();
    }

    public Ec2Client ec2() { return build(Ec2Client.builder()); }

    public SsmClient ssm() { return build(SsmClient.builder()); }

    public S3Client s3() { return build(S3Client.builder()); }

    /** An S3 client for another region, under the same credentials: a bucket answers only from its own. */
    public S3Client s3In(String otherRegion) {
        return S3Client.builder().region(Region.of(otherRegion)).credentialsProvider(credentials.get()).build();
    }

    public DynamoDbClient dynamoDb() { return build(DynamoDbClient.builder()); }

    /**
     * A DynamoDB client that gives up after {@code apiCallTimeout}, retries included. For the
     * status write that precedes terminating an instance: with the network gone — one of the ways
     * a CLI dies — the SDK's default retries would hold the termination back.
     */
    public DynamoDbClient dynamoDb(Duration apiCallTimeout) {
        return build(DynamoDbClient.builder().overrideConfiguration(o -> o.apiCallTimeout(apiCallTimeout)));
    }

    public CloudFormationClient cloudFormation() { return build(CloudFormationClient.builder()); }

    public StsClient sts() { return build(StsClient.builder()); }

    public ImagebuilderClient imageBuilder() { return build(ImagebuilderClient.builder()); }

    /** IAM is global; the region only selects the endpoint. */
    public IamClient iam() { return build(IamClient.builder()); }

    private <B extends AwsClientBuilder<B, C>, C> C build(B builder) {
        builder.region(Region.of(region.get()));
        builder.credentialsProvider(credentials.get());
        return builder.build();
    }

    private static <T> Supplier<T> memoize(Supplier<T> supplier) {
        return new Supplier<>() {
            private T value;

            @Override
            public synchronized T get() {
                if (value == null) {
                    value = supplier.get();
                }
                return value;
            }
        };
    }
}
