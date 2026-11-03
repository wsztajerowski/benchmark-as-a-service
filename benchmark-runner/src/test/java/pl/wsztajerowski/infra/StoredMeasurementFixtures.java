package pl.wsztajerowski.infra;

import pl.wsztajerowski.baas.model.TestMeasurements;
import pl.wsztajerowski.baas.model.StoredMeasurement;

import java.util.Map;

final class StoredMeasurementFixtures {

    private StoredMeasurementFixtures() {}

    static StoredMeasurement jmh(String method) {
        return jmh(method, Map.of(), 1234.5);
    }

    /** One variant of a {@code @Param} sweep: same job, method and mode, its own params and score. */
    static StoredMeasurement jmh(String method, Map<String, String> params, double score) {
        return TestMeasurements.jmh()
            .jobId("jmh-20260819_090000")
            .benchmarkClass("pl.wsztajerowski.fake.Incrementing_Synchronized")
            .benchmarkMethod(method)
            .params(params)
            .score(score)
            .scoreError(67.8)
            .tags(Map.of("project", "lynx-journal", "type", "jmh"))
            .resultPath("main/jmh/20260819_090000")
            .resultJsonKey("main/jmh/20260819_090000/jmh-result.json")
            .environmentJsonKey("main/jmh/20260819_090000/environment.json")
            .build();
    }
}
