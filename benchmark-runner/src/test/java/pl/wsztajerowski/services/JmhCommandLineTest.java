package pl.wsztajerowski.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.wsztajerowski.options.CommonSharedOptions;
import pl.wsztajerowski.options.JmhRun;
import pl.wsztajerowski.process.BenchmarkProcessBuilder;

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
        return process(dir, asyncLib, args).commands();
    }

    private static BenchmarkProcessBuilder process(Path dir, Path asyncLib, String... args) {
        var run = new JmhRun(dir.resolve("b.jar"), List.of(args), dir, asyncLib);
        return new JmhSubcommandService((s, l) -> { }, m -> { },
            new CommonSharedOptions(dir.resolve("r"), "id", Instant.now(), "p", Map.of()), run)
            .jmhProcess();
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
                "-pr", "async:event=wall;dir=" + out,
                "-prof=jfr:dir=" + out);
    }

    /** The library reaches JMH through the environment, on Linux and macOS alike, never as a libPath key. */
    @Test
    void theAsyncLibraryIsOnTheLibraryPathOfEveryJmhProcess(@TempDir Path dir) throws IOException {
        Path lib = Files.createFile(dir.resolve("libasyncProfiler.so"));
        Path libDirectory = dir.toAbsolutePath();

        for (String[] args : List.of(new String[] {"MyBenchmark"}, new String[] {"MyBenchmark", "-prof", "async"},
                new String[] {"-lprof"})) {
            var process = process(dir, lib, args);
            assertThat(process.pathPrefixes())
                .containsExactlyInAnyOrderEntriesOf(Map.of("LD_LIBRARY_PATH", libDirectory, "JAVA_LIBRARY_PATH", libDirectory));
            assertThat(process.commands()).noneMatch(token -> token.contains("libPath"));
        }
    }

    /** A listing asks for no result file: JMH writes none, and the runner reads none back. */
    @Test
    void aProfilerListingIsTheListingAlone(@TempDir Path dir) {
        assertThat(commandLine(dir, dir.resolve("none.so"), "--lprof"))
            .containsExactly("java", "-jar", dir.resolve("b.jar").toString(), "--lprof");
    }

    /** JMH resolves a profiler by its class name too; written so, a JFR recording must still land where it is uploaded. */
    @Test
    void aClassNamedJfrIsPointedAtTheProfilerOutputToo(@TempDir Path dir) {
        String out = dir.resolve("profiler-output").toAbsolutePath().toString();

        assertThat(commandLine(dir, dir.resolve("none.so"), "MyBenchmark", "-prof", "org.openjdk.jmh.profile.JavaFlightRecorderProfiler"))
            .endsWith("-prof", "org.openjdk.jmh.profile.JavaFlightRecorderProfiler:dir=" + out);
    }

    /** A job that failed after profiling still ships the profiles of what ran. */
    @Test
    void profilerArtifactsAreUploadedBeforeAFailedExitIsReported(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("profiler-output/Foo-Throughput"));
        Files.writeString(dir.resolve("profiler-output/Foo-Throughput/profile.jfr"), "jfr");
        java.util.List<Path> saved = new java.util.ArrayList<>();
        var run = new JmhRun(dir.resolve("absent.jar"), List.of("Foo"), dir, dir.resolve("none.so"));
        var sut = new JmhSubcommandService((storagePath, local) -> saved.add(storagePath), m -> { },
            new CommonSharedOptions(Path.of("r"), "id", Instant.now(), "p", Map.of()), run);

        assertThatThrownBy(sut::executeCommand).hasMessageContaining("non-zero code: 1");
        assertThat(saved).contains(Path.of("r/Foo-Throughput/profile.jfr"));
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
