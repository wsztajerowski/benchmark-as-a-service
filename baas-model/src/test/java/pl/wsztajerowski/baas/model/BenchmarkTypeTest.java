package pl.wsztajerowski.baas.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkTypeTest {

    @Test
    void theCommandNamesAreTheOnesBaasRunHasAlwaysAccepted() {
        assertThat(BenchmarkType.commandNames()).containsExactly("jmh", "jmh-with-async", "jmh-with-prof", "jcstress");
    }

    @Test
    void eachTypeNamesTheFileItsProcessOutputIsUploadedAs() {
        assertThat(BenchmarkType.JMH.processOutputName()).isEqualTo("jmh-output.txt");
        assertThat(BenchmarkType.JMH_WITH_ASYNC.processOutputName()).isEqualTo("jmh-with-async-output.txt");
        assertThat(BenchmarkType.JMH_WITH_PROF.processOutputName()).isEqualTo("jmh-profiler-output.txt");
        assertThat(BenchmarkType.JCSTRESS.processOutputName()).isEqualTo("jcstress-output.txt");
    }
}
