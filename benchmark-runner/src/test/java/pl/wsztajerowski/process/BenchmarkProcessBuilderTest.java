package pl.wsztajerowski.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

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

    /** A library directory goes first, and whatever the runner inherited still follows it. */
    @Test
    void aPathPrefixKeepsTheInheritedValueAfterIt() {
        var builder = BenchmarkProcessBuilder.benchmarkProcessBuilder(Path.of("b.jar"))
            .prependToPathVariable("LD_LIBRARY_PATH", Path.of("/app/lib"))
            .prependToPathVariable("JAVA_LIBRARY_PATH", Path.of("/app/lib"));
        Map<String, String> environment = new HashMap<>(Map.of("LD_LIBRARY_PATH", "/usr/local/lib", "JAVA_LIBRARY_PATH", ""));

        builder.applyPathPrefixes(environment);

        assertThat(environment)
            .containsEntry("LD_LIBRARY_PATH", "/app/lib" + File.pathSeparator + "/usr/local/lib")
            .containsEntry("JAVA_LIBRARY_PATH", "/app/lib");
    }
}
