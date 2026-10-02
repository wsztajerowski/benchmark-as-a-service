package pl.wsztajerowski.baas.runs;

import pl.wsztajerowski.baas.model.MeasurementItemMapper;
import pl.wsztajerowski.baas.model.ResultKeys;
import pl.wsztajerowski.baas.model.RunItem;
import pl.wsztajerowski.baas.model.RunItemMapper;
import pl.wsztajerowski.baas.model.RunStatus;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * {@link RunRecorder} over the results table. The terminal guard below is the same expression
 * user-data's {@code run_status} evaluates, so the CLI and the instance agree on what "terminal"
 * means; both read the vocabulary from {@link RunStatus}.
 */
public class DynamoDbRunRecorder implements RunRecorder {

    /**
     * True unless the stored status is terminal. {@code failed:<n>} is matched by prefix, so exit
     * codes need no enumeration; {@code status} is a reserved word, hence {@code #status}.
     */
    public static final String NOT_TERMINAL =
        "(attribute_not_exists(#status) OR NOT (#status IN (:completed, :timedOut, :cancelled, :launchFailed)"
            + " OR begins_with(#status, :failedPrefix)))";

    private static final int PAGE_SIZE = 100;

    private final DynamoDbClient client;
    private final String tableName;
    private final Supplier<Instant> clock;

    public DynamoDbRunRecorder(DynamoDbClient client, String tableName) {
        this(client, tableName, Instant::now);
    }

    DynamoDbRunRecorder(DynamoDbClient client, String tableName, Supplier<Instant> clock) {
        this.client = client;
        this.tableName = tableName;
        this.clock = clock;
    }

    @Override
    public void reserve(RunItem run) {
        var update = new Update().set(RunItemMapper.STATUS, RunStatus.LAUNCHING).stamp(clock.get());
        RunItemMapper.identityAttributes(run).forEach(update::set);
        // A run id is unique by construction; refusing a second reservation keeps it that way
        // even if that ever stopped holding.
        client.updateItem(update.request(run, "attribute_not_exists(#pk)", false));
    }

    @Override
    public Write launched(RunItem run, String instanceId) {
        var update = new Update()
            .set(RunItemMapper.STATUS, RunStatus.LAUNCHED)
            .set(RunItemMapper.INSTANCE_ID, instanceId)
            .stamp(clock.get());
        // Only from launching: the instance's own `running` may already have landed, and a
        // delayed `launched` must not move the status backwards.
        update.value(":launching", RunStatus.LAUNCHING);
        return attempt(update.request(run, "attribute_exists(#pk) AND #status = :launching", false));
    }

    @Override
    public Write launchFailed(RunItem run, String errorCode) {
        var update = new Update().set(RunItemMapper.STATUS, RunStatus.LAUNCH_FAILED).stamp(clock.get());
        if (errorCode != null) {
            update.set(RunItemMapper.ERROR_CODE, errorCode);
        }
        return attempt(update.request(run, "attribute_exists(#pk) AND " + NOT_TERMINAL, true));
    }

    @Override
    public Write stop(RunItem run, String status) {
        var update = new Update().set(RunItemMapper.STATUS, status).stamp(clock.get());
        return attempt(update.request(run, "attribute_exists(#pk) AND " + NOT_TERMINAL, true));
    }

    @Override
    public Optional<RunItem> read(RunItem run) {
        var item = client.getItem(r -> r
            .tableName(tableName)
            .key(RunItemMapper.key(run))
            .consistentRead(true)).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(RunItemMapper.fromItem(item));
    }

    @Override
    public Optional<RunItem> find(String runId) {
        var items = client.query(QueryRequest.builder()
            .tableName(tableName)
            .indexName(ResultKeys.REQUEST_ID_INDEX_NAME)
            .keyConditionExpression("#pk = :pk AND #sk = :run")
            .expressionAttributeNames(Map.of("#pk", MeasurementItemMapper.GSI1PK, "#sk", MeasurementItemMapper.GSI1SK))
            .expressionAttributeValues(Map.of(
                ":pk", AttributeValue.fromS(ResultKeys.requestIndexPartitionKey(runId)),
                ":run", AttributeValue.fromS(ResultKeys.RUN_INDEX_SORT_KEY)))
            .build()).items();
        return items.stream().findFirst().map(RunItemMapper::fromItem);
    }

    /**
     * Pages the one {@code RUN} partition newest first and filters client-side. Filtering in the
     * request would not save the read — DynamoDB charges for what it reads, and applies
     * {@code Limit} before a filter — so paging until enough rows match is the work either way.
     */
    @Override
    public List<RunItem> newestFirst(Predicate<RunItem> filter, int limit) {
        var request = QueryRequest.builder()
            .tableName(tableName)
            .keyConditionExpression("#pk = :pk")
            .expressionAttributeNames(Map.of("#pk", MeasurementItemMapper.PK))
            .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS(ResultKeys.RUN_PARTITION_KEY)))
            .scanIndexForward(false)
            .limit(PAGE_SIZE)
            .build();
        List<RunItem> kept = new ArrayList<>();
        for (var page : client.queryPaginator(request)) {
            for (var item : page.items()) {
                RunItem run = RunItemMapper.fromItem(item);
                if (filter.test(run)) {
                    kept.add(run);
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
            return set(RunItemMapper.UPDATED_AT, ResultKeys.formatTimestamp(now));
        }

        void value(String placeholder, String value) {
            values.put(placeholder, AttributeValue.fromS(value));
        }

        UpdateItemRequest request(RunItem run, String condition, boolean terminalGuard) {
            // DynamoDB rejects a name or value no expression uses, so #status is registered only
            // when the condition reads it.
            if (condition.contains("#status")) {
                names.put("#status", RunItemMapper.STATUS);
            }
            if (terminalGuard) {
                values.put(":completed", AttributeValue.fromS(RunStatus.COMPLETED));
                values.put(":timedOut", AttributeValue.fromS(RunStatus.TIMED_OUT));
                values.put(":cancelled", AttributeValue.fromS(RunStatus.CANCELLED));
                values.put(":launchFailed", AttributeValue.fromS(RunStatus.LAUNCH_FAILED));
                values.put(":failedPrefix", AttributeValue.fromS(RunStatus.FAILED_PREFIX));
            }
            return UpdateItemRequest.builder()
                .tableName(tableName)
                .key(RunItemMapper.key(run))
                .updateExpression("SET " + String.join(", ", assignments))
                .conditionExpression(condition)
                .expressionAttributeNames(names)
                .expressionAttributeValues(values)
                .build();
        }
    }
}
