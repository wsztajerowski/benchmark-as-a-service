package pl.wsztajerowski.baas.jobs;

import pl.wsztajerowski.baas.model.MeasurementItemMapper;
import pl.wsztajerowski.baas.model.ResultKeys;
import pl.wsztajerowski.baas.model.JobItem;
import pl.wsztajerowski.baas.model.JobItemMapper;
import pl.wsztajerowski.baas.model.JobStatus;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * {@link JobRecorder} over the results table. The terminal guard below is the same expression
 * user-data's {@code job_status} evaluates, so the CLI and the instance agree on what "terminal"
 * means; both read the vocabulary from {@link JobStatus}.
 */
public class DynamoDbJobRecorder implements JobRecorder {

    private static final String FAILED_PREFIX_VALUE = ":failedPrefix";

    /**
     * The values {@link #NOT_TERMINAL} names, by placeholder: one per {@link JobStatus#EXACT_TERMINAL}
     * status, then the {@code failed:} prefix. Generated, so a terminal status added to
     * {@link JobStatus} reaches the guard the CLI and the instance both evaluate.
     */
    public static final Map<String, String> NOT_TERMINAL_VALUES = notTerminalValues();

    /**
     * True unless the stored status is terminal. {@code failed:<n>} is matched by prefix, so exit
     * codes need no enumeration; {@code status} is a reserved word, hence {@code #status}.
     */
    public static final String NOT_TERMINAL =
        "(attribute_not_exists(#status) OR NOT (#status IN ("
            + String.join(", ", NOT_TERMINAL_VALUES.keySet().stream().filter(k -> !k.equals(FAILED_PREFIX_VALUE)).toList())
            + ") OR begins_with(#status, " + FAILED_PREFIX_VALUE + ")))";

    private static Map<String, String> notTerminalValues() {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < JobStatus.EXACT_TERMINAL.size(); i++) {
            values.put(":terminal" + i, JobStatus.EXACT_TERMINAL.get(i));
        }
        values.put(FAILED_PREFIX_VALUE, JobStatus.FAILED_PREFIX);
        return java.util.Collections.unmodifiableMap(values);
    }

    private static final int PAGE_SIZE = 100;

    private final DynamoDbClient client;
    private final String tableName;
    private final Supplier<Instant> clock;

    public DynamoDbJobRecorder(DynamoDbClient client, String tableName) {
        this(client, tableName, Instant::now);
    }

    DynamoDbJobRecorder(DynamoDbClient client, String tableName, Supplier<Instant> clock) {
        this.client = client;
        this.tableName = tableName;
        this.clock = clock;
    }

    @Override
    public void reserve(JobItem job) {
        var update = new Update().set(JobItemMapper.STATUS, JobStatus.LAUNCHING).stamp(clock.get());
        JobItemMapper.identityAttributes(job).forEach(update::set);
        // A job id is unique by construction; refusing a second reservation keeps it that way
        // even if that ever stopped holding.
        client.updateItem(update.request(job, "attribute_not_exists(#pk)", false));
    }

    @Override
    public Write launched(JobItem job, String instanceId) {
        var update = new Update()
            .set(JobItemMapper.STATUS, JobStatus.LAUNCHED)
            .set(JobItemMapper.INSTANCE_ID, instanceId)
            .stamp(clock.get());
        // Only from launching: the instance's own `running` may already have landed, and a
        // delayed `launched` must not move the status backwards.
        update.value(":launching", JobStatus.LAUNCHING);
        return attempt(update.request(job, "attribute_exists(#pk) AND #status = :launching", false));
    }

    @Override
    public Write launchFailed(JobItem job, String errorCode) {
        var update = new Update().set(JobItemMapper.STATUS, JobStatus.LAUNCH_FAILED).stamp(clock.get());
        if (errorCode != null) {
            update.set(JobItemMapper.ERROR_CODE, errorCode);
        }
        return attempt(update.request(job, "attribute_exists(#pk) AND " + NOT_TERMINAL, true));
    }

    @Override
    public Write stop(JobItem job, String status) {
        var update = new Update().set(JobItemMapper.STATUS, status).stamp(clock.get());
        return attempt(update.request(job, "attribute_exists(#pk) AND " + NOT_TERMINAL, true));
    }

    @Override
    public Optional<JobItem> read(JobItem job) {
        var item = client.getItem(r -> r
            .tableName(tableName)
            .key(JobItemMapper.key(job))
            .consistentRead(true)).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(JobItemMapper.fromItem(item));
    }

    @Override
    public Optional<JobItem> find(String jobId) {
        var items = client.query(QueryRequest.builder()
            .tableName(tableName)
            .indexName(ResultKeys.JOB_ID_INDEX_NAME)
            .keyConditionExpression("#pk = :pk AND #sk = :job")
            .expressionAttributeNames(Map.of("#pk", MeasurementItemMapper.GSI1PK, "#sk", MeasurementItemMapper.GSI1SK))
            .expressionAttributeValues(Map.of(
                ":pk", AttributeValue.fromS(ResultKeys.jobIndexPartitionKey(jobId)),
                ":job", AttributeValue.fromS(ResultKeys.JOB_INDEX_SORT_KEY)))
            .build()).items();
        return items.stream().findFirst().map(JobItemMapper::fromItem);
    }

    /**
     * Pages the one {@code JOB} partition newest first and filters client-side. Filtering in the
     * request would not save the read — DynamoDB charges for what it reads, and applies
     * {@code Limit} before a filter — so paging until enough rows match is the work either way.
     */
    @Override
    public List<JobItem> newestFirst(Predicate<JobItem> filter, int limit) {
        var request = QueryRequest.builder()
            .tableName(tableName)
            .keyConditionExpression("#pk = :pk")
            .expressionAttributeNames(Map.of("#pk", MeasurementItemMapper.PK))
            .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS(ResultKeys.JOB_PARTITION_KEY)))
            .scanIndexForward(false)
            .limit(PAGE_SIZE)
            .build();
        List<JobItem> kept = new ArrayList<>();
        for (var page : client.queryPaginator(request)) {
            for (var item : page.items()) {
                JobItem job = JobItemMapper.fromItem(item);
                if (filter.test(job)) {
                    kept.add(job);
                    if (kept.size() >= limit) {
                        return kept;
                    }
                }
            }
        }
        return kept;
    }

    private Write attempt(UpdateItemRequest request) {
        try {
            client.updateItem(request);
            return Write.WRITTEN;
        } catch (ConditionalCheckFailedException e) {
            return Write.REFUSED;
        }
    }

    /** One {@code SET} expression, its names and values, built together so none is left unused. */
    private final class Update {
        private final List<String> assignments = new ArrayList<>();
        private final Map<String, String> names = new HashMap<>(Map.of("#pk", MeasurementItemMapper.PK));
        private final Map<String, AttributeValue> values = new HashMap<>();

        Update set(String attribute, String value) {
            return set(attribute, AttributeValue.fromS(value));
        }

        Update set(String attribute, AttributeValue value) {
            String placeholder = "a" + assignments.size();
            names.put("#" + placeholder, attribute);
            values.put(":" + placeholder, value);
            assignments.add("#" + placeholder + " = :" + placeholder);
            return this;
        }

        Update stamp(Instant now) {
            return set(JobItemMapper.UPDATED_AT, ResultKeys.formatTimestamp(now));
        }

        void value(String placeholder, String value) {
            values.put(placeholder, AttributeValue.fromS(value));
        }

        UpdateItemRequest request(JobItem job, String condition, boolean terminalGuard) {
            // DynamoDB rejects a name or value no expression uses, so #status is registered only
            // when the condition reads it.
            if (condition.contains("#status")) {
                names.put("#status", JobItemMapper.STATUS);
            }
            if (terminalGuard) {
                NOT_TERMINAL_VALUES.forEach((placeholder, status) -> values.put(placeholder, AttributeValue.fromS(status)));
            }
            return UpdateItemRequest.builder()
                .tableName(tableName)
                .key(JobItemMapper.key(job))
                .updateExpression("SET " + String.join(", ", assignments))
                .conditionExpression(condition)
                .expressionAttributeNames(names)
                .expressionAttributeValues(values)
                .build();
        }
    }
}
