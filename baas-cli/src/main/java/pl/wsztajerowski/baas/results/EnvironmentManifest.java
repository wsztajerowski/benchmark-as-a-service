package pl.wsztajerowski.baas.results;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A job's {@code environment.json}: since schemaVersion 6 the measurement environment in seven
 * groups, and nothing about the job's identity. Fields are held as {@code group.field}; a flat
 * manifest from an older schema parses too, its fields under no group, so a comparison across a
 * schema change still shows what it can.
 */
public record EnvironmentManifest(String jobId, Map<String, String> fields) {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SCHEMA_VERSION = "schemaVersion";

    /** The groups, in the order the manifest writes and every view prints them. */
    public static final List<String> GROUPS = List.of("machine", "cpu", "memory", "os", "jvm", "tools", "tunables");

    public static EnvironmentManifest parse(String jobId, String json) {
        Map<String, Object> raw;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = JSON.readValue(json, Map.class);
            raw = parsed;
        } catch (IOException e) {
            throw new UncheckedIOException(
                new IOException("environment.json of job " + jobId + " is not valid JSON", e));
        }

        Map<String, String> fields = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (value instanceof Map<?, ?> group) {
                group.forEach((field, fieldValue) ->
                    fields.put(key + "." + field, fieldValue == null ? "" : String.valueOf(fieldValue)));
            } else {
                fields.put(key, value == null ? "" : String.valueOf(value));
            }
        });
        return new EnvironmentManifest(jobId, fields);
    }

    /**
     * Absent, or present and unrecognised, is not an error: the diff still works, and the caller
     * decides whether to say the two manifests were written to different rules.
     */
    public Optional<String> schemaVersion() {
        return Optional.ofNullable(fields.get(SCHEMA_VERSION));
    }

    public Optional<String> amiId() {
        return Optional.ofNullable(fields.get("machine.amiId")).filter(value -> !value.isBlank());
    }

    /** One group's fields, by their name within the group, in the order the manifest holds them. */
    public Map<String, String> group(String group) {
        Map<String, String> fieldsOfGroup = new LinkedHashMap<>();
        fields.forEach((key, value) -> {
            if (key.startsWith(group + ".")) {
                fieldsOfGroup.put(key.substring(group.length() + 1), value);
            }
        });
        return fieldsOfGroup;
    }

    /**
     * The differing fields of two manifests, by group then field, groups in {@link #GROUPS} order
     * and any field outside a group (an older schema's) last, under {@code ""}. {@code schemaVersion}
     * is never a difference — the caller warns about it. A field present in only one manifest is
     * reported with an empty string for the side that lacks it: hiding it would defeat the point.
     */
    public static Map<String, Map<String, Difference>> diff(EnvironmentManifest a, EnvironmentManifest b) {
        Set<String> keys = new LinkedHashSet<>(a.fields().keySet());
        keys.addAll(b.fields().keySet());
        keys.remove(SCHEMA_VERSION);

        Map<String, Map<String, Difference>> byGroup = new LinkedHashMap<>();
        for (String group : GROUPS) {
            byGroup.put(group, new LinkedHashMap<>());
        }
        byGroup.put("", new LinkedHashMap<>());
        for (String key : keys) {
            String left = a.fields().getOrDefault(key, "");
            String right = b.fields().getOrDefault(key, "");
            if (left.equals(right)) {
                continue;
            }
            int dot = key.indexOf('.');
            String group = dot > 0 && GROUPS.contains(key.substring(0, dot)) ? key.substring(0, dot) : "";
            String field = group.isEmpty() ? key : key.substring(dot + 1);
            byGroup.get(group).put(field, new Difference(left, right));
        }
        byGroup.values().removeIf(Map::isEmpty);
        return byGroup;
    }

    public record Difference(String left, String right) {
    }
}
