package pl.wsztajerowski.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.wsztajerowski.options.CommonSharedOptions;
import pl.wsztajerowski.options.JmhRun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What reaches {@code java -jar}: the runner's result format and file, then the user's arguments as
 * given, with only the {@code async}/{@code jfr} profilers pointed at the job's artifacts. A dropped
 * or rewritten argument used to pass every unit test and show up only in a paid job's numbers
 * (review T3).
 */
class JmhCommandLineTest {

    private static List<String> commandLine(Path dir, Path asyncLib, String... args) {
        var run = new JmhRun(dir.resolve("b.jar"), List.of(args), dir, asyncLib);
        return new JmhSubcommandService((s, l) -> { }, m -> { },
            new CommonSharedOptions(dir.resolve("r"), "id", Instant.now(), "p", Map.of()), run)
            .jmhProcess().commands();
    }

    @Test
    void theArgumentsFollowTheResultFlagsUnchanged(@TempDir Path dir) {
        assertThat(commandLine(dir, dir.resolve("none.so"),
            "MyBenchmark", "-f", "1", "-v", "EXTRA", "-p", "size=10", "-jvmArgs", "-Xmx2g -XX:+UseZGC", "-prof", "gc"))
            .containsExactly("java", "-jar", dir.resolve("b.jar").toString(),
                "-rf", "json", "-rff", dir.resolve("jmh-results.json").toString(),
                "MyBenchmark", "-f", "1", "-v", "EXTRA", "-p", "size=10", "-jvmArgs", "-Xmx2g -XX:+UseZGC", "-prof", "gc");
    }

    @Test
    void asyncAndJfrArePointedAtTheProfilerOutputAfterTheUsersKeys(@TempDir Path dir) throws IOException {
        Path lib = Files.createFile(dir.resolve("libasyncProfiler.so"));
        String out = dir.resolve("profiler-output").toAbsolutePath().toString();

        assertThat(commandLine(dir, lib, "MyBenchmark", "-pr", "async:event=wall", "-prof=jfr"))
            .endsWith("MyBenchmark",
                "-pr", "async:event=wall;dir=" + out + ";libPath=" + lib.toAbsolutePath(),
                "-prof=jfr:dir=" + out);
    }

    @Test
    void noProfilerAddsNothing(@TempDir Path dir) {
        assertThat(commandLine(dir, dir.resolve("none.so"), "MyBenchmark")).hasSize(8);
    }

    @Test
    void asyncWithoutTheLibraryFailsBeforeTheBenchmarkStarts(@TempDir Path dir) {
        assertThatThrownBy(() -> commandLine(dir, dir.resolve("none.so"), "-prof", "async"))
            .hasMessageContaining("-prof async needs async-profiler")
            .hasMessageContaining("none.so");
    }
}
