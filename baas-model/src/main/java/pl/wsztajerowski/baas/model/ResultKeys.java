package pl.wsztajerowski.baas.model;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The only place a DynamoDB key is constructed. Encoding a key by hand anywhere else is how a
 * query silently returns zero rows instead of failing to compile.
 *
 * <p>{@code sk} is benchmark-major then chronological, which serves three patterns from one
 * ordering: the latest result for a benchmark, a benchmark's history in order, and grouping.
 */
public final class ResultKeys {

    public static final String PK_PREFIX = "RESULT#";
    public static final String JCSTRESS_SK_PREFIX = "JCSTRESS#";
    public static final String SEPARATOR = "#";
    public static final String REQUEST_ID_INDEX_NAME = "requestId-index";

    /**
     * Fixed width, always three fractional digits, always UTC. Instant.toString() omits trailing
     * zero fractions, which makes keys of differing length that misorder as strings — and the
     * failure surfaces as missing rows, not as a formatting error.
     */
    private static final DateTimeFormatter TIMESTAMP =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private ResultKeys() {}

    public static String partitionKey(String project) {
        return PK_PREFIX + project;
    }

    public static String sortKey(StoredMeasurement measurement) {
        String timestamp = formatTimestamp(measurement.createdAt());
        if (measurement.kind() == MeasurementKind.JCSTRESS) {
            return JCSTRESS_SK_PREFIX + timestamp + SEPARATOR + measurement.requestId();
        }
        String key = measurement.benchmarkClass()
            + SEPARATOR + measurement.benchmarkMethod()
            + SEPARATOR + modeOrEmpty(measurement.mode())
            + SEPARATOR + timestamp
            + SEPARATOR + measurement.requestId();
        // A @Param sweep's variants share every field above — one run, one timestamp — so without
        // this they collide: BatchWriteItem rejects the whole batch, and across batches the last
        // variant silently overwrites the rest. Appended, and only when present, rather than a
        // fixed field like mode: every key written before params were stored stays byte-identical,
        // and nothing parses a key, so a trailing optional field costs nothing to read.
        return measurement.params().isEmpty() ? key : key + SEPARATOR + formatParams(measurement.params());
    }

    /**
     * {@code k=v} pairs sorted by key, joined by {@code ,} — one canonical text per combination,
     * shared by the sort key and by {@code baas results}' grouping. A value containing {@code ,}
     * or {@code =} could in principle alias another combination; the result would be a rejected
     * duplicate key, loud rather than wrong, so it is not escaped.
     */
    public static String formatParams(Map<String, String> params) {
        return new TreeMap<>(params).entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining(","));
    }

    /**
     * Every run of every project shares this one partition: the questions it answers — what is
     * in flight, what happened to my run — are installation-wide, and one {@code Query} answers
     * them newest first. Projects stay an attribute.
     */
    public static final String RUN_PARTITION_KEY = "RUN";

    /**
     * A run item's {@code gsi1sk}. A measurement's index sort key always contains {@code #}
     * (class#method#mode, or {@code JCSTRESS#<id>}), so it can never equal this, and a lookup by
     * run id can address the run item exactly instead of relying on sort order.
     */
    public static final String RUN_INDEX_SORT_KEY = "RUN";

    /**
     * {@code <createdAt>#<runId>}, the timestamp in the same fixed-width format measurements use,
     * so the partition reads chronologically. The CLI hands this to user-data verbatim: the shell
     * never rebuilds it, because the {@code CREATED_AT} it holds is {@link Instant#toString()},
     * whose width varies.
     */
    public static String runSortKey(Instant createdAt, String runId) {
        return formatTimestamp(createdAt) + SEPARATOR + runId;
    }

    public static String requestIndexPartitionKey(String requestId) {
        return requestId;
    }

    public static String requestIndexSortKey(StoredMeasurement measurement) {
        if (measurement.kind() == MeasurementKind.JCSTRESS) {
            return JCSTRESS_SK_PREFIX + measurement.requestId();
        }
        return measurement.benchmarkClass()
            + SEPARATOR + measurement.benchmarkMethod()
            + SEPARATOR + modeOrEmpty(measurement.mode());
    }

    /**
     * A run with {@code -bm thrpt,avgt} produces two results whose class+method are identical;
     * without mode in the key those rows are differentiated only by a millisecond timestamp, and a
     * same-millisecond collision silently overwrites one via PutItem. Rendered as an empty string
     * rather than omitted so the key always has the same number of {@code #}-separated fields — a
     * variable field count would be worse than a blank one.
     */
    private static String modeOrEmpty(String mode) {
        return mode == null ? "" : mode;
    }

    public static String formatTimestamp(Instant instant) {
        return TIMESTAMP.format(instant);
    }
}
