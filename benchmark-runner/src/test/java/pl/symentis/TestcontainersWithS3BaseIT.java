package pl.symentis;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@Testcontainers(disabledWithoutDocker = true)
public class TestcontainersWithS3BaseIT {
    protected static final String TEST_BUCKET_NAME = "test-bucket";
    private static final String LOCAL_STACK_VERSION = "0.12.16";
    @Container
    protected final static LocalStackContainer LOCAL_STACK_CONTAINER =
        new LocalStackContainer(DockerImageName.parse("localstack/localstack:" + LOCAL_STACK_VERSION))
            .withLabel("java.wonderland.testcontainer", "localstack")
            .withServices(LocalStackContainer.Service.S3)
            .withEnv("DEFAULT_REGION", "eu-central-1");
    protected S3Client awsS3Client;

    @BeforeEach
    void createBucketAndClient() throws IOException, InterruptedException {
        LOCAL_STACK_CONTAINER.execInContainer("awslocal", "s3", "mb", "s3://" + TEST_BUCKET_NAME);
        awsS3Client = S3Client
            .builder()
            .endpointOverride(LOCAL_STACK_CONTAINER.getEndpoint())
            .region(Region.of(LOCAL_STACK_CONTAINER.getRegion()))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(LOCAL_STACK_CONTAINER.getAccessKey(), LOCAL_STACK_CONTAINER.getSecretKey())
                )
            )
            .build();
    }

    @AfterEach
    void removeBucket() throws IOException, InterruptedException {
        LOCAL_STACK_CONTAINER.execInContainer("awslocal", "s3", "rb", "s3://" + TEST_BUCKET_NAME, "--force");
    }

    public JSONArray listObjectsInTestBucket() {
        try {
            var execResult = LOCAL_STACK_CONTAINER.execInContainer("awslocal", "s3api", "list-objects", "--bucket", TEST_BUCKET_NAME);
            assertThat(execResult.getExitCode())
                .withFailMessage("Listing bucket objects failed: %s ", execResult.getStderr())
                .isZero();
            return new JSONObject(execResult.getStdout())
                .getJSONArray("Contents");
        } catch (Exception e) {
            fail("Failed to list objects in test bucket", e);
        }
        return new JSONArray();
    }

    protected Path createPathForTestResource(String name) {
        return Optional.ofNullable(ClassLoader.getSystemResource(name))
            .map(url -> {
                try {
                    return url.toURI();
                } catch (URISyntaxException e) {
                    throw new RuntimeException(e);
                }
            })
            .map(Paths::get)
            .orElseThrow(IllegalArgumentException::new);
    }

}