package pl.wsztajerowski.baas.model;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The schema contract. An explicit mapper rather than the Enhanced Client, because key encoding
 * needs exact control and an incompatible change should break compilation here rather than return
 * zero rows at runtime.
 */
public final class MeasurementItemMapper {

    public static final int MAX_ITEM_BYTES = 400 * 1024;

    static final String PK = "pk";
    static final String SK = "sk";
    static final String GSI1PK = "gsi1pk";
    static final String GSI1SK = "gsi1sk";
    static final String PROJECT = "project";
    static final String REQUEST_ID = "requestId";
    static final String CREATED_AT = "createdAt";
    static final String KIND = "kind";
    static final String BENCHMARK_CLASS = "benchmarkClass";
    static final String BENCHMARK_METHOD = "benchmarkMethod";
    static final String MODE = "mode";
    static final String SCORE = "score";
    static final String SCORE_ERROR = "scoreError";
    static final String SCORE_UNIT = "scoreUnit";
    static final String SECONDARY_METRICS = "secondaryMetrics";
    static final String JCSTRESS = "jcstress";
    static final String TAGS = "tags";
    static final String RESULT_PATH = "resultPath";
    static final String RESULT_JSON_KEY = "resultJsonKey";
    static final String ENVIRONMENT_JSON_KEY = "environmentJsonKey";

    private static final String TOTAL_TESTS = "totalTests";
    private static final String PASSED_TESTS = "passedTests";
    private static final String FAILED_TESTS = "failedTests";
    private static final String ERROR_TESTS = "errorTests";
    private static final String FAILED = "failed";
    private static final String ERRORS = "errors";
    private static final String INTERESTING = "interesting";
    private static final String METRIC_SCORE = "score";
    private static final String METRIC_UNIT = "unit";

    private MeasurementItemMapper() {}

    public static Map<String, AttributeValue> toItem(StoredMeasurement m) {
        Map<String, AttributeValue> item = new LinkedHashMap<>();

        item.put(PK, s(ResultKeys.partitionKey(m.project())));
        item.put(SK, s(ResultKeys.sortKey(m)));
        item.put(GSI1PK, s(ResultKeys.requestIndexPartitionKey(m.requestId())));
        item.put(GSI1SK, s(ResultKeys.requestIndexSortKey(m)));

        item.put(PROJECT, s(m.project()));
        item.put(REQUEST_ID, s(m.requestId()));
        item.put(CREATED_AT, s(ResultKeys.formatTimestamp(m.createdAt())));
        item.put(KIND, s(m.kind().name()));

        putIfPresent(item, BENCHMARK_CLASS, m.benchmarkClass());
        putIfPresent(item, BENCHMARK_METHOD, m.benchmarkMethod());
        putIfPresent(item, MODE, m.mode());
        putIfPresent(item, SCORE_UNIT, m.scoreUnit());
        putIfPresent(item, RESULT_PATH, m.resultPath());
        putIfPresent(item, RESULT_JSON_KEY, m.resultJsonKey());
        putIfPresent(item, ENVIRONMENT_JSON_KEY, m.environmentJsonKey());

        if (m.score() != null) {
            item.put(SCORE, n(m.score()));
        }
        if (m.scoreError() != null) {
            item.put(SCORE_ERROR, n(m.scoreError()));
        }
        if (!m.secondaryMetrics().isEmpty()) {
            item.put(SECONDARY_METRICS, AttributeValue.fromM(
                m.secondaryMetrics().entrySet().stream().collect(Collectors.toMap(
                    Map.Entry::getKey,
                    e -> AttributeValue.fromM(Map.of(
                        METRIC_SCORE, n(e.getValue().score()),
                        METRIC_UNIT, s(e.getValue().unit())))))));
        }
        if (!m.tags().isEmpty()) {
            item.put(TAGS, AttributeValue.fromM(
                m.tags().entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> s(e.getValue())))));
        }
        if (m.jcstress() != null) {
            item.put(JCSTRESS, AttributeValue.fromM(toJcstressItem(m.jcstress())));
        }

        int size = serializedSize(item);
        if (size > MAX_ITEM_BYTES) {
            throw new IllegalStateException(
                "Measurement for request " + m.requestId() + " serializes to " + size
                    + " bytes, above DynamoDB's 400 KB item limit. Refusing to truncate — "
                    + "reduce the tag set or move the payload to S3.");
        }
        return item;
    }

    public static StoredMeasurement fromItem(Map<String, AttributeValue> item) {
        return new StoredMeasurement(
            str(item, PROJECT),
            str(item, REQUEST_ID),
            Instant.parse(str(item, CREATED_AT)),
            MeasurementKind.valueOf(str(item, KIND)),
            str(item, BENCHMARK_CLASS),
            str(item, BENCHMARK_METHOD),
            str(item, MODE),
            dbl(item, SCORE),
            dbl(item, SCORE_ERROR),
            str(item, SCORE_UNIT),
            secondaryMetricsFrom(item),
            jcstressFrom(item),
            tagsFrom(item),
            str(item, RESULT_PATH),
            str(item, RESULT_JSON_KEY),
            str(item, ENVIRONMENT_JSON_KEY));
    }

    /**
     * Approximates DynamoDB's own accounting: attribute names plus values, recursing into maps.
     * Exactness is not required — the guard exists to fail loudly well before the real limit.
     */
    public static int serializedSize(Map<String, AttributeValue> item) {
        int total = 0;
        for (Map.Entry<String, AttributeValue> entry : item.entrySet()) {
            total += utf8(entry.getKey()) + valueSize(entry.getValue());
        }
        return total;
    }

    private static Map<String, AttributeValue> toJcstressItem(JcstressSummary j) {
        Map<String, AttributeValue> nested = new LinkedHashMap<>();
        nested.put(TOTAL_TESTS, n(j.totalTests()));
        nested.put(PASSED_TESTS, n(j.passedTests()));
        nested.put(FAILED_TESTS, n(j.failedTests()));
        nested.put(ERROR_TESTS, n(j.errorTests()));
        nested.put(FAILED, stringMap(j.failed()));
        nested.put(ERRORS, stringMap(j.errors()));
        nested.put(INTERESTING, stringMap(j.interesting()));
        return nested;
    }

    private static JcstressSummary jcstressFrom(Map<String, AttributeValue> item) {
        AttributeValue value = item.get(JCSTRESS);
        if (value == null || !value.hasM()) {
            return null;
        }
        Map<String, AttributeValue> nested = value.m();
        return new JcstressSummary(
            intOf(nested, TOTAL_TESTS),
            intOf(nested, PASSED_TESTS),
            intOf(nested, FAILED_TESTS),
            intOf(nested, ERROR_TESTS),
            stringMapFrom(nested, FAILED),
            stringMapFrom(nested, ERRORS),
            stringMapFrom(nested, INTERESTING));
    }

    private static Map<String, SecondaryMetric> secondaryMetricsFrom(Map<String, AttributeValue> item) {
        AttributeValue value = item.get(SECONDARY_METRICS);
        if (value == null || !value.hasM()) {
            return Map.of();
        }
        return value.m().entrySet().stream().collect(Collectors.toMap(
            Map.Entry::getKey,
            e -> new SecondaryMetric(
                Double.parseDouble(e.getValue().m().get(METRIC_SCORE).n()),
                e.getValue().m().get(METRIC_UNIT).s())));
    }

    private static Map<String, String> tagsFrom(Map<String, AttributeValue> item) {
        AttributeValue value = item.get(TAGS);
        if (value == null || !value.hasM()) {
            return Map.of();
        }
        return value.m().entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().s()));
    }

    private static AttributeValue stringMap(Map<String, String> source) {
        return AttributeValue.fromM(source.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> s(e.getValue()))));
    }

    private static Map<String, String> stringMapFrom(Map<String, AttributeValue> nested, String name) {
        AttributeValue value = nested.get(name);
        if (value == null || !value.hasM()) {
            return Map.of();
        }
        return value.m().entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().s()));
    }

    private static void putIfPresent(Map<String, AttributeValue> item, String name, String value) {
        if (value != null) {
            item.put(name, s(value));
        }
    }

    private static String str(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? null : value.s();
    }

    private static Double dbl(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? null : Double.valueOf(value.n());
    }

    private static int intOf(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? 0 : Integer.parseInt(value.n());
    }

    private static int valueSize(AttributeValue value) {
        if (value.s() != null) return utf8(value.s());
        if (value.n() != null) return utf8(value.n());
        if (value.hasM()) return serializedSize(value.m());
        return 0;
    }

    private static int utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    private static AttributeValue n(Number value) {
        return AttributeValue.fromN(String.valueOf(value));
    }
}
