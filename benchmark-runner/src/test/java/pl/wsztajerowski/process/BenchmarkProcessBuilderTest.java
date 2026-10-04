package pl.wsztajerowski.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkProcessBuilderTest {

    /**
     * The process output may name a directory that does not exist yet. Only its parent is created:
     * creating the path itself made a directory named like the file, which the output could then not
     * be redirected to (finding A14).
     */
    @Test
    void processOutputLandsInADirectoryCreatedForIt(@TempDir Path tmp) throws Exception {
        Path output = tmp.resolve("missing/dir/output.txt");

        Process process = BenchmarkProcessBuilder.benchmarkProcessBuilder(tmp.resolve("absent.jar"))
            .withOutputPath(output)
            .buildAndStartProcess();
        process.waitFor();

        assertThat(output).isRegularFile();
        assertThat(Files.readString(output)).isNotEmpty();
    }
}
