package pl.wsztajerowski.infra;

import pl.wsztajerowski.baas.model.MeasurementKind;
import pl.wsztajerowski.baas.model.StoredMeasurement;

import java.time.Instant;
import java.util.Map;

final class StoredMeasurementFixtures {

    private StoredMeasurementFixtures() {}

    static StoredMeasurement jmh(String method) {
        return new StoredMeasurement(
            "lynx-journal",
            "jmh-20260819_090000",
            Instant.parse("2026-08-19T09:00:00.000Z"),
            MeasurementKind.JMH,
            "pl.wsztajerowski.fake.Incrementing_Synchronized",
            method,
            "thrpt",
            1234.5,
            67.8,
            "ops/s",
            Map.of(),
            null,
            Map.of("project", "lynx-journal", "type", "jmh"),
            "main/jmh/20260819_090000",
            "main/jmh/20260819_090000/jmh-result.json",
            "main/jmh/20260819_090000/environment.json");
    }
}
