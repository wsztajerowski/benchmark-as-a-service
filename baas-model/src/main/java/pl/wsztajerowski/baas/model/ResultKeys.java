package pl.wsztajerowski.baas.model;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

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
        return measurement.benchmarkClass()
            + SEPARATOR + measurement.benchmarkMethod()
            + SEPARATOR + modeOrEmpty(measurement.mode())
            + SEPARATOR + timestamp
            + SEPARATOR + measurement.requestId();
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
