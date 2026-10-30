package pl.wsztajerowski.baas.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class BenchmarkTypeTest {

    @Test
    void oneTypePerLibrary() {
        assertThat(BenchmarkType.commandNames()).containsExactly("jmh", "jcstress");
    }

    @Test
    void eachTypeNamesTheFileItsProcessOutputIsWrittenToAndUploadedAs() {
        assertThat(BenchmarkType.JMH.workingOutputName()).isEqualTo("jmh-output.txt");
        assertThat(BenchmarkType.JMH.processOutputName()).isEqualTo("jmh-output.txt");
        assertThat(BenchmarkType.JCSTRESS.workingOutputName()).isEqualTo("jcstress-output.txt");
        assertThat(BenchmarkType.JCSTRESS.processOutputName()).isEqualTo("jcstress-output.txt");
    }

    /** Stored jobs of the retired profiled types still hold these, and readers must recognise them. */
    @Test
    void theRetiredTypesOutputNamesAreKept() {
        assertThat(BenchmarkType.LEGACY_PROCESS_OUTPUT_NAMES)
            .containsExactly("jmh-with-async-output.txt", "jmh-profiler-output.txt");
    }

    @Test
    void aTypeIsFoundByItsCommandName() {
        assertThat(BenchmarkType.fromCommandName("jcstress")).isEqualTo(BenchmarkType.JCSTRESS);
        assertThatIllegalArgumentException()
            .isThrownBy(() -> BenchmarkType.fromCommandName("jmh-with-async")).withMessageContaining("jmh-with-async");
    }
}
