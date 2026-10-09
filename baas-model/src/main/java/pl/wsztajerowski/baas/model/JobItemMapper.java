package pl.wsztajerowski.baas.model;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The job item's attribute names, key and identity fields — the counterpart of
 * {@link MeasurementItemMapper} for {@code pk = JOB}. Writes are conditional {@code UpdateItem}s
 * built by the CLI, so this maps attributes rather than whole items on the way in.
 */
public final class JobItemMapper {

    public static final String JOB_ID = "jobId";
    public static final String PROJECT = "project";
    public static final String CREATED_AT = "createdAt";
    public static final String RESULT_PATH = "resultPath";
    public static final String INSTANCE_TYPE = "instanceType";
    public static final String STATUS = "status";
    public static final String INSTANCE_ID = "instanceId";
    public static final String ERROR_CODE = "errorCode";
    public static final String TAGS = "tags";
    public static final String UPDATED_AT = "updatedAt";

    private JobItemMapper() {}

    public static Map<String, AttributeValue> key(Instant createdAt, String jobId) {
        return Map.of(
            MeasurementItemMapper.PK, s(ResultKeys.JOB_PARTITION_KEY),
            MeasurementItemMapper.SK, s(ResultKeys.jobSortKey(createdAt, jobId)));
    }

    public static Map<String, AttributeValue> key(JobItem job) {
        return key(job.createdAt(), job.jobId());
    }

    /**
     * What the reservation sets besides the key and the status: the index keys and every identity
     * field. Absent optional values are left out, since an {@code UpdateItem} cannot set an empty
     * {@link AttributeValue}.
     */
    public static Map<String, AttributeValue> identityAttributes(JobItem job) {
        Map<String, AttributeValue> attributes = new LinkedHashMap<>();
        attributes.put(MeasurementItemMapper.GSI1PK, s(ResultKeys.jobIndexPartitionKey(job.jobId())));
        attributes.put(MeasurementItemMapper.GSI1SK, s(ResultKeys.JOB_INDEX_SORT_KEY));
        attributes.put(JOB_ID, s(job.jobId()));
        attributes.put(PROJECT, s(job.project()));
        attributes.put(CREATED_AT, s(ResultKeys.formatTimestamp(job.createdAt())));
        putIfPresent(attributes, RESULT_PATH, job.resultPath());
        putIfPresent(attributes, INSTANCE_TYPE, job.instanceType());
        if (!job.tags().isEmpty()) {
            attributes.put(TAGS, AttributeValue.fromM(job.tags().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> s(e.getValue())))));
        }
        return attributes;
    }

    /** Whether an item read from the table is a job item rather than a measurement. */
    public static boolean isJobItem(Map<String, AttributeValue> item) {
        AttributeValue pk = item.get(MeasurementItemMapper.PK);
        return pk != null && ResultKeys.JOB_PARTITION_KEY.equals(pk.s());
    }

    public static JobItem fromItem(Map<String, AttributeValue> item) {
        if (!isJobItem(item)) {
            throw new IllegalArgumentException("Not a job item (pk=" + str(item, MeasurementItemMapper.PK)
                + ", sk=" + str(item, MeasurementItemMapper.SK) + ")");
        }
        String updatedAt = str(item, UPDATED_AT);
        return new JobItem(
            str(item, JOB_ID),
            str(item, PROJECT),
            Instant.parse(str(item, CREATED_AT)),
            str(item, RESULT_PATH),
            str(item, INSTANCE_TYPE),
            str(item, STATUS),
            str(item, INSTANCE_ID),
            str(item, ERROR_CODE),
            tagsFrom(item),
            updatedAt == null ? null : Instant.parse(updatedAt));
    }

    private static Map<String, String> tagsFrom(Map<String, AttributeValue> item) {
        AttributeValue value = item.get(TAGS);
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

    private static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }
}
