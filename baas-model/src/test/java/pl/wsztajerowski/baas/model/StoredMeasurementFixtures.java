package pl.wsztajerowski.baas.model;

import java.util.Map;

public final class StoredMeasurementFixtures {

    private StoredMeasurementFixtures() {}

    public static StoredMeasurement jmh() {
        return jmh(Map.of());
    }

    /** The same measurement as one variant of a {@code @Param} sweep. */
    public static StoredMeasurement jmh(Map<String, String> params) {
        return TestMeasurements.jmh()
            .jobId("jmh-20260817_220706")
            .createdAt("2026-08-17T22:07:06.123Z")
            .benchmarkClass("pl.wsztajerowski.fake.Incrementing_Synchronized")
            .benchmarkMethod("incrementUsingSynchronized")
            .params(params)
            .score(14075511.867)
            .scoreError(10632927.824)
            .secondaryMetrics(Map.of("·gc.alloc.rate", new SecondaryMetric(1234.5, "MB/sec")))
            .tags(Map.of("project", "lynx-journal", "type", "jmh", "jdkVersion", "25.0.4"))
            .resultPath("main/jmh/20260817_220706")
            .resultJsonKey("main/jmh/20260817_220706/jmh-result.json")
            .environmentJsonKey("main/jmh/20260817_220706/environment.json")
            .build();
    }

    public static StoredMeasurement jcstress() {
        return TestMeasurements.jcstress()
            .jobId("jcstress-20260817_221500")
            .createdAt("2026-08-17T22:15:00.000Z")
            .jcstress(new JcstressSummary(12, 10, 1, 1,
                Map.of("SomeTest", "FORBIDDEN"),
                Map.of("OtherTest", "ERROR"),
                Map.of("ThirdTest", "INTERESTING")))
            .tags(Map.of("project", "lynx-journal", "type", "jcstress"))
            .resultPath("main/jcstress/20260817_221500")
            .environmentJsonKey("main/jcstress/20260817_221500/environment.json")
            .build();
    }
}
