package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retrieval half of the thin-item bargain: measurements drop {@code rawData} on the way into
 * DynamoDB, so the whole run has to come back out of S3 intact.
 */
@Testcontainers(disabledWithoutDocker = true)
class S3DownloadIT {

    @Container
    private static final LocalStackContainer LOCAL_STACK =
        new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8"))
            .withServices(LocalStackContainer.Service.S3);

    private static final String RESULT_PATH = "main/jmh/20260819_090000";

    private S3Client s3;
    private String bucket;
    private S3UploadService storage;

    @BeforeEach
    void createBucketWithARun() {
        s3 = S3Client.builder()
            .endpointOverride(LOCAL_STACK.getEndpoint())
            .region(Region.of(LOCAL_STACK.getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(LOCAL_STACK.getAccessKey(), LOCAL_STACK.getSecretKey())))
            .forcePathStyle(true)
            .build();

        bucket = "baas-test-" + UUID.randomUUID();
        s3.createBucket(r -> r.bucket(bucket));
        storage = new S3UploadService(s3);

        put(RESULT_PATH + "/jmh-result.json", "{\"rawData\":[[1.0,2.0]]}");
        put(RESULT_PATH + "/environment.json", "{\"schemaVersion\":1}");
        put(RESULT_PATH + "/jmh-output.txt", "benchmark output");
        put(RESULT_PATH + "/packages.txt", "some-rpm-1.0");
        put(RESULT_PATH + "/logs/gc.log", "gc log");
        put(RESULT_PATH + "/com.example.Bench.run-Throughput/flame.html", "<html/>");
        put("main/jmh/20260101_000000/jmh-result.json", "a different run");
    }

    @Test
    void listsEveryArtifactOfOneRunAndNothingFromAnother() {
        var keys = storage.listKeys(bucket, RESULT_PATH + "/");

        assertThat(keys).hasSize(6);
        assertThat(keys).allSatisfy(key -> assertThat(key).startsWith(RESULT_PATH + "/"));
    }

    @Test
    void downloadsTheWholeRunPreservingItsLayout(@TempDir Path destination) {
        String prefix = RESULT_PATH + "/";
        for (String key : storage.listKeys(bucket, prefix)) {
            storage.download(bucket, key, destination.resolve(key.substring(prefix.length())));
        }

        assertThat(destination.resolve("jmh-result.json")).exists();
        assertThat(destination.resolve("environment.json")).exists();
        assertThat(destination.resolve("jmh-output.txt")).exists();
        assertThat(destination.resolve("packages.txt")).exists();
        assertThat(destination.resolve("logs/gc.log")).exists();
        assertThat(destination.resolve("com.example.Bench.run-Throughput/flame.html")).exists();
    }

    @Test
    void dataAbsentFromTheStoredItemIsRecoverableFromTheDownloadedJson(@TempDir Path destination) throws Exception {
        Path resultJson = destination.resolve("jmh-result.json");

        storage.download(bucket, RESULT_PATH + "/jmh-result.json", resultJson);

        assertThat(Files.readString(resultJson))
            .as("rawData is dropped from the DynamoDB item, so it has to survive here")
            .contains("rawData");
    }

    @Test
    void anUnknownRunListsNothingSoNoDirectoryIsEverCreated() {
        assertThat(storage.listKeys(bucket, "main/jmh/no-such-run/"))
            .as("emptiness is how the command detects an unknown run before writing anything")
            .isEmpty();
    }

    private void put(String key, String body) {
        s3.putObject(r -> r.bucket(bucket).key(key), RequestBody.fromString(body));
    }
}
