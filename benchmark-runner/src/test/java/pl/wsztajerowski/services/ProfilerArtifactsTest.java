package pl.wsztajerowski.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProfilerArtifactsTest {

    private static final Path STORAGE = Path.of("jobs/lynx-journal/20260724T120000000Z-a3f9c21b");

    @TempDir
    Path root;

    private final Map<Path, Path> uploaded = new LinkedHashMap<>();

    @Test
    void uploadsEveryFileBelowTheOutputRootAtItsRelativePath() throws Exception {
        Path flame = write("Bench.run-Throughput/flame-cpu-forward.html");
        Path jfr = write("Bench.run-Throughput/jfr-cpu.jfr");

        ProfilerArtifacts.upload(uploaded::put, root, STORAGE);

        assertThat(uploaded)
            .containsEntry(STORAGE.resolve("Bench.run-Throughput/flame-cpu-forward.html"), flame)
            .containsEntry(STORAGE.resolve("Bench.run-Throughput/jfr-cpu.jfr"), jfr)
            .hasSize(2);
    }

    // Issue #115: JMH names a @Param benchmark's directory after its parameters too, and a runner
    // that predicted `<benchmark>-<Mode>` uploaded nothing for it. The walk knows no names.
    @Test
    void uploadsTheDirectoriesOfAParamSweepWhateverJmhNamedThem() throws Exception {
        Path first = write("Bench.run-Throughput-size-10/flame-cpu-forward.html");
        Path second = write("Bench.run-Throughput-size-20/flame-cpu-forward.html");
        Path nested = write("Bench.run-Throughput-size-20/nested/summary.txt");

        ProfilerArtifacts.upload(uploaded::put, root, STORAGE);

        assertThat(uploaded)
            .containsEntry(STORAGE.resolve("Bench.run-Throughput-size-10/flame-cpu-forward.html"), first)
            .containsEntry(STORAGE.resolve("Bench.run-Throughput-size-20/flame-cpu-forward.html"), second)
            .containsEntry(STORAGE.resolve("Bench.run-Throughput-size-20/nested/summary.txt"), nested)
            .hasSize(3);
    }

    // A profiler's own logs are also shipped under logs/ by JobLogs; the copy beside the profile
    // is kept so each tree holds everything that was written to it.
    @Test
    void uploadsAProfilersLogBesideItsProfile() throws Exception {
        Path log = write("Bench.run-Throughput/async.log");

        ProfilerArtifacts.upload(uploaded::put, root, STORAGE);

        assertThat(uploaded).containsEntry(STORAGE.resolve("Bench.run-Throughput/async.log"), log);
    }

    @Test
    void aMissingOutputRootUploadsNothingAndDoesNotFail() {
        ProfilerArtifacts.upload(uploaded::put, root.resolve("async-output"), STORAGE);

        assertThat(uploaded).isEmpty();
    }

    private Path write(String relative) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, relative);
    }
}
