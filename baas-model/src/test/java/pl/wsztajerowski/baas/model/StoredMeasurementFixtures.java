package pl.wsztajerowski.baas.model;

import java.time.Instant;
import java.util.Map;

final class StoredMeasurementFixtures {

    private StoredMeasurementFixtures() {}

    static StoredMeasurement jmh() {
        return new StoredMeasurement(
            "lynx-journal",
            "jmh-20260817_220706",
            Instant.parse("2026-08-17T22:07:06.123Z"),
            MeasurementKind.JMH,
            "pl.wsztajerowski.fake.Incrementing_Synchronized",
            "incrementUsingSynchronized",
            "thrpt",
            14075511.867,
            10632927.824,
            "ops/s",
            Map.of("·gc.alloc.rate", new SecondaryMetric(1234.5, "MB/sec")),
            null,
            Map.of("project", "lynx-journal", "type", "jmh", "jdk", "25.0.4"),
            "main/jmh/20260817_220706",
            "main/jmh/20260817_220706/jmh-result.json",
            "main/jmh/20260817_220706/environment.json");
    }

    static StoredMeasurement jcstress() {
        return new StoredMeasurement(
            "lynx-journal",
            "jcstress-20260817_221500",
            Instant.parse("2026-08-17T22:15:00.000Z"),
            MeasurementKind.JCSTRESS,
            null, null, null, null, null, null,
            Map.of(),
            new JcstressSummary(12, 10, 1, 1,
                Map.of("SomeTest", "FORBIDDEN"),
                Map.of("OtherTest", "ERROR"),
                Map.of("ThirdTest", "INTERESTING")),
            Map.of("project", "lynx-journal", "type", "jcstress"),
            "main/jcstress/20260817_221500",
            null,
            "main/jcstress/20260817_221500/environment.json");
    }
}
