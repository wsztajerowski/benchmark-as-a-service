package pl.wsztajerowski.baas.infra;

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

public class AwsClientFactory {

    private final Region region;
    private final String profile;

    public AwsClientFactory(String region, String profile) {
        this.region = Region.of(region);
        this.profile = profile;
    }

    public Ec2Client ec2() { return build(Ec2Client.builder()); }

    public SsmClient ssm() { return build(SsmClient.builder()); }

    public S3Client s3() { return build(S3Client.builder()); }

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

    /**
     * Every client gets the region, and the named profile when there is one; with none, the default
     * credential chain applies, so {@code AWS_PROFILE} and ambient credentials still work.
     */
    private <B extends AwsClientBuilder<B, C>, C> C build(B builder) {
        builder.region(region);
        if (profile != null) {
            builder.credentialsProvider(ProfileCredentialsProvider.create(profile));
        }
        return builder.build();
    }
}
