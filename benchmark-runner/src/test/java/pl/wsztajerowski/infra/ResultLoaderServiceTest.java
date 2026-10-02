package pl.wsztajerowski.infra;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.wsztajerowski.entities.jmh.JmhResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResultLoaderServiceTest {

    @TempDir
    Path tempDir;

    /** JMH writes params as an object of strings, and leaves it out for a benchmark without any. */
    @Test
    void readsParamsFromJmhsOwnJsonAndToleratesTheirAbsence() throws Exception {
        Path json = Files.writeString(tempDir.resolve("jmh-result.json"), """
            [
              { "benchmark": "com.acme.MapLookup.get", "mode": "thrpt",
                "params": { "impl": "hash", "size": "10" },
                "primaryMetric": { "score": 182431207.4, "scoreUnit": "ops/s" } },
              { "benchmark": "com.acme.MapLookup.baseline", "mode": "thrpt",
                "primaryMetric": { "score": 3201447.0, "scoreUnit": "ops/s" } }
            ]
            """);

        List<JmhResult> results = ResultLoaderService.getResultLoaderService().loadJmhResults(json);

        assertThat(results.get(0).params()).isEqualTo(Map.of("impl", "hash", "size", "10"));
        assertThat(results.get(1).params()).isNull();
    }
}
