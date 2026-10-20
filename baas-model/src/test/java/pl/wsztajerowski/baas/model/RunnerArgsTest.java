package pl.wsztajerowski.baas.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunnerArgsTest {

    @ParameterizedTest
    @ValueSource(strings = {"--tag", "--project", "-id", "--job-id", "--results-table", "--s3-bucket", "-m",
        "--mongo-connection-string", "--dynamodb-endpoint", "--s3-service-endpoint", "--benchmark-path",
        "--created-at", "--result-path"})
    void everyOptionTheCliSetsIsFoundAmongTheBenchmarkArguments(String option) {
        assertThat(RunnerArgs.firstSetByTheCli(List.of("MyBenchmark", "-f", "1", option, "x"))).contains(option);
    }

    @Test
    void anAttachedValueIsFoundToo() {
        assertThat(RunnerArgs.firstSetByTheCli(List.of("MyBenchmark", "--tag=imageVersion=1.0.0")))
            .contains("--tag");
    }

    @Test
    void ordinaryBenchmarkArgumentsPass() {
        assertThat(RunnerArgs.firstSetByTheCli(List.of(
            "MyBenchmark", "-f", "1", "-wi", "1", "-i", "3", "-bm", "thrpt", "--mode", "sanity",
            "-jvmArgs", "-mx512m", "-e", "Excluded", "-t", "max"))).isEmpty();
    }

    @Test
    void aShortOptionDoesNotMatchALongerOneThatStartsWithIt() {
        assertThat(RunnerArgs.firstSetByTheCli(List.of("-mx512m", "-idle"))).isEmpty();
    }
}
