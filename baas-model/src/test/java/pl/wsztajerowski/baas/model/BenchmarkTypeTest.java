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

    /** The runner writes every JMH type's output to one file and uploads it under the type's own name. */
    @Test
    void eachTypeNamesTheFileItsProcessOutputIsWrittenTo() {
        assertThat(BenchmarkType.JMH.workingOutputName()).isEqualTo("jmh-output.txt");
        assertThat(BenchmarkType.JMH_WITH_ASYNC.workingOutputName()).isEqualTo("jmh-output.txt");
        assertThat(BenchmarkType.JMH_WITH_PROF.workingOutputName()).isEqualTo("jmh-output.txt");
        assertThat(BenchmarkType.JCSTRESS.workingOutputName()).isEqualTo("jcstress-output.txt");
    }

    @Test
    void aTypeIsFoundByItsCommandName() {
        assertThat(BenchmarkType.fromCommandName("jmh-with-async")).isEqualTo(BenchmarkType.JMH_WITH_ASYNC);
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
            .isThrownBy(() -> BenchmarkType.fromCommandName("jmh-async")).withMessageContaining("jmh-async");
    }
}
