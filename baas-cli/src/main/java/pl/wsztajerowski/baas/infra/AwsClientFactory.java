package pl.wsztajerowski.baas.infra;

import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.ssm.SsmClient;

public class AwsClientFactory {

    private final Region region;
    private final String profile;

    public AwsClientFactory(String region, String profile) {
        this.region = Region.of(region);
        this.profile = profile;
    }

    public Ec2Client ec2() {
        var b = Ec2Client.builder().region(region);
        if (profile != null) b.credentialsProvider(ProfileCredentialsProvider.create(profile));
        return b.build();
    }

    public SsmClient ssm() {
        var b = SsmClient.builder().region(region);
        if (profile != null) b.credentialsProvider(ProfileCredentialsProvider.create(profile));
        return b.build();
    }

    public S3Client s3() {
        var b = S3Client.builder().region(region);
        if (profile != null) b.credentialsProvider(ProfileCredentialsProvider.create(profile));
        return b.build();
    }

    public CloudFormationClient cloudFormation() {
        var b = CloudFormationClient.builder().region(region);
        if (profile != null) b.credentialsProvider(ProfileCredentialsProvider.create(profile));
        return b.build();
    }
}
