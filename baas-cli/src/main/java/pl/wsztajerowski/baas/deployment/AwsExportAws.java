package pl.wsztajerowski.baas.deployment;

import pl.wsztajerowski.baas.aws.AwsClientFactory;
import pl.wsztajerowski.baas.aws.CloudFormationService;
import pl.wsztajerowski.baas.aws.S3UploadService;
import pl.wsztajerowski.baas.model.dynamodb.MeasurementItemMapper;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.sts.StsClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@link ExportAws} over the deployer's clients. Unlike {@code AwsTeardownAws}, a client lives for the
 * whole command: an export makes one call per object, and building a client for each would cost more
 * than the call. SDK clients are thread-safe, which the download pool relies on.
 */
final class AwsExportAws implements ExportAws {

    private final Lazy<S3Client> s3;
    private final Lazy<DynamoDbClient> dynamoDb;
    private final Lazy<CloudFormationClient> cloudFormation;
    private final Lazy<StsClient> sts;

    AwsExportAws(AwsClientFactory factory) {
        this(factory::s3, factory::dynamoDb, factory::cloudFormation, factory::sts);
    }

    /** Over clients built elsewhere — LocalStack's, in the round-trip IT. */
    AwsExportAws(Supplier<S3Client> s3, Supplier<DynamoDbClient> dynamoDb,
                 Supplier<CloudFormationClient> cloudFormation, Supplier<StsClient> sts) {
        this.s3 = new Lazy<>(s3);
        this.dynamoDb = new Lazy<>(dynamoDb);
        this.cloudFormation = new Lazy<>(cloudFormation);
        this.sts = new Lazy<>(sts);
    }

    @Override
    public String account() {
        return sts.get().getCallerIdentity().account();
    }

    @Override
    public void scan(String table, Consumer<Map<String, AttributeValue>> item) {
        dynamoDb.get().scanPaginator(r -> r.tableName(table)).items().forEach(item);
    }

    @Override
    public List<StoredObject> list(String bucket, String prefix) {
        List<StoredObject> objects = new ArrayList<>();
        for (var page : s3.get().listObjectsV2Paginator(r -> r.bucket(bucket).prefix(prefix))) {
            page.contents().forEach(o -> objects.add(new StoredObject(o.key(), o.size())));
        }
        return objects;
    }

    @Override
    public void download(String bucket, String key, Path destination) {
        new S3UploadService(s3.get()).download(bucket, key, destination);
    }

    @Override
    public Map<String, String> stackParameters(String stack) {
        return new CloudFormationService(cloudFormation.get()).getStackParameters(stack);
    }

    @Override
    public boolean tableExists(String table) {
        try {
            dynamoDb.get().describeTable(r -> r.tableName(table));
            return true;
        } catch (ResourceNotFoundException e) {
            return false;
        }
    }

    @Override
    public boolean bucketExists(String bucket) {
        return new S3UploadService(s3.get()).bucketRegion(bucket).isPresent();
    }

    @Override
    public boolean putItemIfAbsent(String table, Map<String, AttributeValue> item) {
        try {
            dynamoDb.get().putItem(r -> r.tableName(table).item(item)
                .conditionExpression("attribute_not_exists(" + MeasurementItemMapper.PK + ")"));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    /** The same conditional write that seeds {@code releases/}: S3 itself refuses a second write. */
    @Override
    public boolean putObjectIfAbsent(String bucket, String key, Path file) {
        try {
            s3.get().putObject(PutObjectRequest.builder().bucket(bucket).key(key).ifNoneMatch("*").build(),
                RequestBody.fromFile(file));
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 412 || e.statusCode() == 409) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public void close() {
        s3.close();
        dynamoDb.close();
        cloudFormation.close();
        sts.close();
    }

    /** A client built on first use, so a command that never lists the bucket builds no S3 client. */
    private static final class Lazy<T extends AutoCloseable> {
        private final Supplier<T> create;
        private T value;

        Lazy(Supplier<T> create) {
            this.create = create;
        }

        synchronized T get() {
            if (value == null) {
                value = create.get();
            }
            return value;
        }

        synchronized void close() {
            if (value != null) {
                try {
                    value.close();
                } catch (Exception ignored) {
                    // closing a client is best effort
                }
            }
        }
    }
}
