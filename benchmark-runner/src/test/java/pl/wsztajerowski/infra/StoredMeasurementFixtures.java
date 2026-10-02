package pl.wsztajerowski.infra;

import pl.wsztajerowski.baas.model.MeasurementKind;
import pl.wsztajerowski.baas.model.StoredMeasurement;

import java.time.Instant;
import java.util.Map;

final class StoredMeasurementFixtures {

    private StoredMeasurementFixtures() {}

    static StoredMeasurement jmh(String method) {
        return jmh(method, Map.of(), 1234.5);
    }

    /** One variant of a {@code @Param} sweep: same run, method and mode, its own params and score. */
    static StoredMeasurement jmh(String method, Map<String, String> params, double score) {
        return new StoredMeasurement(
            "lynx-journal",
            "jmh-20260819_090000",
            Instant.parse("2026-08-19T09:00:00.000Z"),
            MeasurementKind.JMH,
            "pl.wsztajerowski.fake.Incrementing_Synchronized",
            method,
            "thrpt",
            params,
            score,
            67.8,
            "ops/s",
            Map.of(),
            null,
            Map.of("project", "lynx-journal", "type", "jmh"),
            "main/jmh/20260819_090000",
            "main/jmh/20260819_090000/jmh-result.json",
            "main/jmh/20260819_090000/environment.json",
            null);
    }
}
