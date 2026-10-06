package pl.wsztajerowski.baas.jobs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import pl.wsztajerowski.baas.model.MeasurementItemMapper;
import pl.wsztajerowski.baas.model.ResultKeys;
import pl.wsztajerowski.baas.model.JobItem;
import pl.wsztajerowski.baas.model.JobItemMapper;
import pl.wsztajerowski.baas.model.JobStatus;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The job item's conditional writes against a real DynamoDB: the guard that keeps the first
 * terminal status, the {@code launching}-only {@code launched}, and the reservation as the only
 * write that creates an item. These are the rules the in-memory fake in {@code JobSessionTest}
 * imitates, so this is what keeps that fake honest.
 */
@Testcontainers(disabledWithoutDocker = true)
class DynamoDbJobRecorderIT {

    @Container
    private static final LocalStackContainer LOCAL_STACK =
        new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.14.0"))
            .withServices(LocalStackContainer.Service.DYNAMODB);

    private DynamoDbClient client;
    private String tableName;
    private DynamoDbJobRecorder recorder;

    @BeforeEach
    void createTable() {
        client = DynamoDbClient.builder()
            .endpointOverride(LOCAL_STACK.getEndpoint())
            .region(Region.of(LOCAL_STACK.getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(LOCAL_STACK.getAccessKey(), LOCAL_STACK.getSecretKey())))
            .build();
        tableName = "baas-test-results-" + UUID.randomUUID();
        client.createTable(r -> r
            .tableName(tableName)
            .billingMode(BillingMode.PAY_PER_REQUEST)
            .attributeDefinitions(
                attribute(MeasurementItemMapper.PK), attribute(MeasurementItemMapper.SK),
                attribute(MeasurementItemMapper.GSI1PK), attribute(MeasurementItemMapper.GSI1SK))
            .keySchema(key(MeasurementItemMapper.PK, KeyType.HASH), key(MeasurementItemMapper.SK, KeyType.RANGE))
            .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                .indexName(ResultKeys.JOB_ID_INDEX_NAME)
                .keySchema(key(MeasurementItemMapper.GSI1PK, KeyType.HASH), key(MeasurementItemMapper.GSI1SK, KeyType.RANGE))
                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                .build()));
        recorder = new DynamoDbJobRecorder(client, tableName, () -> Instant.parse("2026-10-03T01:02:03.456Z"));
    }

    private static JobItem run(String jobId, String createdAt, String project) {
        return new JobItem(jobId, project, Instant.parse(createdAt), "jobs/" + project + "/" + jobId,
            "c5.2xlarge", JobStatus.LAUNCHING, null, null, Map.of("project", project, "source", "local"), null);
    }

    private final JobItem job = run("20261003T000000000Z-a3f9c21b", "2026-10-03T00:00:00Z", "p");

    @Test
    void theReservationCreatesACompleteItem() {
        recorder.reserve(job);

        var stored = recorder.read(job).orElseThrow();
        assertThat(stored.status()).isEqualTo(JobStatus.LAUNCHING);
        assertThat(stored.project()).isEqualTo("p");
        assertThat(stored.resultPath()).isEqualTo(job.resultPath());
        assertThat(stored.tags()).containsEntry("source", "local");
        assertThat(stored.updatedAt()).isEqualTo(Instant.parse("2026-10-03T01:02:03.456Z"));
    }

    @Test
    void aSecondReservationOfTheSameJobIsRefused() {
        recorder.reserve(job);

        assertThatThrownBy(() -> recorder.reserve(job))
            .isInstanceOf(software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException.class);
    }

    @Test
    void eachTransitionLandsFromAnInFlightStatus() {
        recorder.reserve(job);

        assertThat(recorder.launched(job, "i-1")).isEqualTo(JobRecorder.Write.WRITTEN);
        assertThat(recorder.read(job).orElseThrow().instanceId()).isEqualTo("i-1");
        assertThat(recorder.stop(job, JobStatus.CANCELLED)).isEqualTo(JobRecorder.Write.WRITTEN);
        assertThat(recorder.read(job).orElseThrow().status()).isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    void theFirstTerminalStatusWins() {
        recorder.reserve(job);
        instanceWrites(job, "completed");

        assertThat(recorder.stop(job, JobStatus.CANCELLED)).isEqualTo(JobRecorder.Write.REFUSED);
        assertThat(recorder.launchFailed(job, "X")).isEqualTo(JobRecorder.Write.REFUSED);
        assertThat(recorder.read(job).orElseThrow().status()).isEqualTo(JobStatus.COMPLETED);
    }

    @Test
    void aFailedOutcomeIsTerminalByPrefix() {
        recorder.reserve(job);
        instanceWrites(job, "failed:137");

        assertThat(recorder.stop(job, JobStatus.TIMED_OUT)).isEqualTo(JobRecorder.Write.REFUSED);
    }

    @Test
    void launchedNeverMovesTheStatusBackwards() {
        recorder.reserve(job);
        instanceWrites(job, JobStatus.RUNNING);

        assertThat(recorder.launched(job, "i-1")).isEqualTo(JobRecorder.Write.REFUSED);
        assertThat(recorder.read(job).orElseThrow().status()).isEqualTo(JobStatus.RUNNING);
    }

    @Test
    void noWriteButTheReservationCreatesAnItem() {
        assertThat(recorder.launched(job, "i-1")).isEqualTo(JobRecorder.Write.REFUSED);
        assertThat(recorder.stop(job, JobStatus.CANCELLED)).isEqualTo(JobRecorder.Write.REFUSED);
        assertThat(recorder.launchFailed(job, "X")).isEqualTo(JobRecorder.Write.REFUSED);
        assertThat(recorder.read(job)).isEmpty();
    }

    @Test
    void aFailedLaunchKeepsItsErrorCode() {
        recorder.reserve(job);

        recorder.launchFailed(job, "InsufficientInstanceCapacity");

        var stored = recorder.read(job).orElseThrow();
        assertThat(stored.status()).isEqualTo(JobStatus.LAUNCH_FAILED);
        assertThat(stored.errorCode()).isEqualTo("InsufficientInstanceCapacity");
    }

    @Test
    void aJobIsFoundByItsIdThroughTheIndex() {
        recorder.reserve(job);

        assertThat(recorder.find(job.jobId())).map(JobItem::resultPath).contains(job.resultPath());
        assertThat(recorder.find("no-such-run")).isEmpty();
    }

    @Test
    void listingIsNewestFirstAcrossProjectsAndPagesUntilEnoughMatch() {
        // 250 runs: more than two pages of 100, newest belonging to project b.
        IntStream.range(0, 250).forEach(i -> recorder.reserve(run(
            "run-%03d".formatted(i), Instant.parse("2026-10-03T00:00:00Z").plusSeconds(i).toString(),
            i < 30 ? "a" : "b")));

        var newest = recorder.newestFirst(r -> true, 20);
        assertThat(newest).hasSize(20);
        assertThat(newest.getFirst().jobId()).isEqualTo("run-249");
        assertThat(newest).extracting(JobItem::project).containsOnly("b");

        var olderProject = recorder.newestFirst(r -> "a".equals(r.project()), 20);
        assertThat(olderProject).hasSize(20).extracting(JobItem::project).containsOnly("a");
        assertThat(olderProject.getFirst().jobId()).isEqualTo("run-029");
    }

    /** What user-data's job_status does: status and instance id only, on an existing item. */
    private void instanceWrites(JobItem target, String status) {
        client.updateItem(r -> r
            .tableName(tableName)
            .key(JobItemMapper.key(target))
            .updateExpression("SET #status = :s")
            .conditionExpression("attribute_exists(pk) AND " + DynamoDbJobRecorder.NOT_TERMINAL)
            .expressionAttributeNames(Map.of("#status", "status"))
            .expressionAttributeValues(Map.of(
                ":s", AttributeValue.fromS(status),
                ":completed", AttributeValue.fromS(JobStatus.COMPLETED),
                ":timedOut", AttributeValue.fromS(JobStatus.TIMED_OUT),
                ":cancelled", AttributeValue.fromS(JobStatus.CANCELLED),
                ":launchFailed", AttributeValue.fromS(JobStatus.LAUNCH_FAILED),
                ":failedPrefix", AttributeValue.fromS(JobStatus.FAILED_PREFIX))));
    }

    private static AttributeDefinition attribute(String name) {
        return AttributeDefinition.builder().attributeName(name).attributeType(ScalarAttributeType.S).build();
    }

    private static KeySchemaElement key(String name, KeyType type) {
        return KeySchemaElement.builder().attributeName(name).keyType(type).build();
    }
}
