package pl.wsztajerowski.baas.deployment;

import pl.wsztajerowski.baas.model.TestMeasurements;
import pl.wsztajerowski.baas.model.TestJobs;
import pl.wsztajerowski.baas.testsupport.TestCli;
import pl.wsztajerowski.baas.testsupport.LocalStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.wsztajerowski.baas.TestDeployments;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.model.ResultKeys;
import pl.wsztajerowski.baas.model.dynamodb.JobItemMapper;
import pl.wsztajerowski.baas.model.dynamodb.MeasurementItemMapper;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
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
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sts.StsClient;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Export from one deployment and import into another against real DynamoDB and S3 semantics: the
 * conditional writes, the typed attribute encoding and the key-by-key mirror are what a fake cannot
 * vouch for. The extension part is covered by the fake-backed tests; it needs CloudFormation.
 */
@Testcontainers(disabledWithoutDocker = true)
class ExportImportRoundTripIT {

    private static final String SOURCE = "baas-src";
    private static final String TARGET = "baas-dst";
    private static final String JOB_ID = "20261023T090642860Z-5165200d";
    private static final String JOB = "jobs/p/" + JOB_ID + "/";

    @Container
    private static final LocalStackContainer LOCAL_STACK =
        LocalStack.with(LocalStackContainer.Service.DYNAMODB, LocalStackContainer.Service.S3,
                LocalStackContainer.Service.STS);

    @TempDir
    Path dir;

    private DynamoDbClient dynamoDb;
    private S3Client s3;
    private final StringWriter out = new StringWriter();

    @BeforeEach
    void twoDeployments() {
        var credentials = StaticCredentialsProvider.create(
            AwsBasicCredentials.create(LOCAL_STACK.getAccessKey(), LOCAL_STACK.getSecretKey()));
        dynamoDb = DynamoDbClient.builder().endpointOverride(LOCAL_STACK.getEndpoint())
            .region(Region.of(LOCAL_STACK.getRegion())).credentialsProvider(credentials).build();
        s3 = S3Client.builder().endpointOverride(LOCAL_STACK.getEndpoint())
            .region(Region.of(LOCAL_STACK.getRegion())).credentialsProvider(credentials).forcePathStyle(true).build();
        for (String deployment : List.of(SOURCE, TARGET)) {
            if (s3.listBuckets().buckets().stream().noneMatch(b -> b.name().equals(deployment))) {
                s3.createBucket(r -> r.bucket(deployment));
                createTable(deployment + "-results");
            }
        }
    }

    /** Fresh clients each time: the port owns and closes them. */
    private AwsExportAws aws() {
        var credentials = StaticCredentialsProvider.create(
            AwsBasicCredentials.create(LOCAL_STACK.getAccessKey(), LOCAL_STACK.getSecretKey()));
        var region = Region.of(LOCAL_STACK.getRegion());
        return new AwsExportAws(
            () -> S3Client.builder().endpointOverride(LOCAL_STACK.getEndpoint()).region(region)
                .credentialsProvider(credentials).forcePathStyle(true).build(),
            () -> DynamoDbClient.builder().endpointOverride(LOCAL_STACK.getEndpoint()).region(region)
                .credentialsProvider(credentials).build(),
            () -> { throw new UnsupportedOperationException("no CloudFormation here"); },
            () -> StsClient.builder().endpointOverride(LOCAL_STACK.getEndpoint()).region(region)
                .credentialsProvider(credentials).build());
    }

    @Test
    void aBundleRestoresItemByItemAndObjectByObject() throws Exception {
        var measurement = MeasurementItemMapper.toItem(TestMeasurements.jmh()
            .project("p")
            .jobId(JOB_ID)
            .createdAt("2026-10-23T09:06:42.860Z")
            .benchmarkMethod("m")
            .params(Map.of("size", "10"))
            .score(8234574.731914)
            .scoreError(12.5)
            .tags(Map.of("imageVersion", "1.4.0", "source", "local"))
            .resultPath(JOB.substring(0, JOB.length() - 1))
            .resultJsonKey(JOB + "jmh-result.json")
            .environmentJsonKey(JOB + "environment.json")
            .build());
        var job = TestJobs.job(JOB_ID).createdAt("2026-10-23T09:06:42.860Z").resultPath(JOB.substring(0, JOB.length() - 1))
            .instanceType("c5.large").status("completed").instanceId("i-0abc").tags(Map.of("source", "local"))
            .watchdogSeconds(7500).build();
        Map<String, AttributeValue> jobItem = new LinkedHashMap<>(JobItemMapper.key(job));
        jobItem.putAll(JobItemMapper.identityAttributes(job));
        jobItem.put(JobItemMapper.STATUS, AttributeValue.fromS("completed"));
        dynamoDb.putItem(r -> r.tableName(SOURCE + "-results").item(measurement));
        dynamoDb.putItem(r -> r.tableName(SOURCE + "-results").item(jobItem));
        s3.putObject(r -> r.bucket(SOURCE).key(JOB + "environment.json"), RequestBody.fromString("{\"schemaVersion\":6}"));
        s3.putObject(r -> r.bucket(SOURCE).key(JOB + "input/benchmark.jar"), RequestBody.fromBytes(new byte[2048]));
        s3.putObject(r -> r.bucket(SOURCE).key("releases/7.2.0/benchmark-runner.jar"), RequestBody.fromBytes(new byte[16]));

        Path bundle = dir.resolve("bundle");
        try (var aws = aws()) {
            var outcome = new DeploymentExport(aws, SOURCE, LOCAL_STACK.getRegion(), Instant::now)
                .run(bundle, EnumSet.of(Part.RESULTS, Part.RECORDINGS), false);
            assertThat(outcome.manifest().items()).isEqualTo(2);
            assertThat(outcome.manifest().objects()).isEqualTo(2);
        }

        assertThat(importInto(bundle)).isZero();
        assertThat(items(TARGET)).isEqualTo(items(SOURCE));
        assertThat(keys(TARGET)).containsExactlyInAnyOrder(JOB + "environment.json", JOB + "input/benchmark.jar");
        assertThat(s3.getObjectAsBytes(r -> r.bucket(TARGET).key(JOB + "environment.json")).asUtf8String())
            .isEqualTo("{\"schemaVersion\":6}");
        assertThat(dynamoDb.query(r -> r.tableName(TARGET + "-results").indexName(ResultKeys.JOB_ID_INDEX_NAME)
            .keyConditionExpression("gsi1pk = :j")
            .expressionAttributeValues(Map.of(":j", AttributeValue.fromS(JOB_ID)))).count())
            .as("the index is rebuilt from the restored items").isEqualTo(2);

        out.getBuffer().setLength(0);
        assertThat(importInto(bundle)).isZero();
        assertThat(out.toString()).contains("results     0 written, 2 already present",
            "recordings  0 written, 2 already present");
    }

    private int importInto(Path bundle) throws Exception {
        TestDeployments.write(dir, TARGET);
        var command = new ImportCommand();
        command.awsFor = config -> aws();
        command.context.use(Console.withFlags(new PrintWriter(out, true), false, false));
        var run = TestCli.at(dir).with(ImportCommand.class, command)
            .run("--deployment", TARGET, "admin", "deployment", "import", bundle.toString());
        out.write(run.out());
        return run.exit();
    }

    private Set<Map<String, AttributeValue>> items(String deployment) {
        Set<Map<String, AttributeValue>> items = new HashSet<>();
        dynamoDb.scanPaginator(r -> r.tableName(deployment + "-results")).items()
            .forEach(item -> items.add(new HashMap<>(item)));
        return items;
    }

    private List<String> keys(String bucket) {
        return s3.listObjectsV2(r -> r.bucket(bucket)).contents().stream().map(o -> o.key()).toList();
    }

    private void createTable(String table) {
        dynamoDb.createTable(r -> r
            .tableName(table)
            .billingMode(BillingMode.PAY_PER_REQUEST)
            .attributeDefinitions(string(MeasurementItemMapper.PK), string(MeasurementItemMapper.SK),
                string(MeasurementItemMapper.GSI1PK), string(MeasurementItemMapper.GSI1SK))
            .keySchema(key(MeasurementItemMapper.PK, KeyType.HASH), key(MeasurementItemMapper.SK, KeyType.RANGE))
            .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                .indexName(ResultKeys.JOB_ID_INDEX_NAME)
                .keySchema(key(MeasurementItemMapper.GSI1PK, KeyType.HASH), key(MeasurementItemMapper.GSI1SK, KeyType.RANGE))
                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                .build()));
    }

    private static AttributeDefinition string(String name) {
        return AttributeDefinition.builder().attributeName(name).attributeType(ScalarAttributeType.S).build();
    }

    private static KeySchemaElement key(String name, KeyType type) {
        return KeySchemaElement.builder().attributeName(name).keyType(type).build();
    }
}
