package pl.wsztajerowski.baas.results;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * One run's {@code <result-path>/environment.json} — the observation, as opposed to the
 * declaration in {@code infra/runner-image.yaml}.
 *
 * <p>Held as a flat {@code Map} rather than a record with named fields on purpose: a manifest
 * written by a newer runner carries fields this CLI has never heard of, and those are exactly the
 * ones worth reporting when two runs disagree. A record would silently drop them.
 */
public record EnvironmentManifest(String resultPath, Map<String, String> fields) {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SCHEMA_VERSION = "schemaVersion";

    public static EnvironmentManifest parse(String resultPath, String json) {
        Map<String, Object> raw;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = JSON.readValue(json, Map.class);
            raw = parsed;
        } catch (IOException e) {
            throw new UncheckedIOException(
                new IOException("environment.json for " + resultPath + " is not valid JSON", e));
        }

        Map<String, String> fields = new LinkedHashMap<>();
        raw.forEach((key, value) -> fields.put(key, value == null ? "" : String.valueOf(value)));
        return new EnvironmentManifest(resultPath, fields);
    }

    /**
     * Absent, or present and unrecognised, is not an error: the diff still works field by field,
     * and the caller decides whether to say the two manifests were written to different rules.
     */
    public Optional<String> schemaVersion() {
        return Optional.ofNullable(fields.get(SCHEMA_VERSION));
    }

    /**
     * Fields that differ between two manifests, keyed by field name. A field present in only one
     * manifest is reported with an empty string for the side that lacks it — that is a difference
     * in the environment record, and hiding it would defeat the point.
     */
    public static Map<String, Difference> diff(EnvironmentManifest a, EnvironmentManifest b) {
        Map<String, Difference> differences = new LinkedHashMap<>();
        for (String key : new TreeSet<>(union(a, b))) {
            String left = a.fields().getOrDefault(key, "");
            String right = b.fields().getOrDefault(key, "");
            if (!left.equals(right)) {
                differences.put(key, new Difference(left, right));
            }
        }
        return differences;
    }

    private static TreeSet<String> union(EnvironmentManifest a, EnvironmentManifest b) {
        var keys = new TreeSet<>(a.fields().keySet());
        keys.addAll(b.fields().keySet());
        return keys;
    }

    public record Difference(String left, String right) {
    }
}
