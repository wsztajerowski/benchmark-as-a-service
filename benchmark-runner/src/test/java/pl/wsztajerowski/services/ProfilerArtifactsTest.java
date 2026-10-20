package pl.wsztajerowski.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProfilerArtifactsTest {

    private static final Path STORAGE = Path.of("jobs/lynx-journal/20260724T120000000Z-a3f9c21b/Bench.run-Throughput");

    @TempDir
    Path root;

    private final Map<Path, Path> uploaded = new LinkedHashMap<>();

    @Test
    void uploadsEveryFileOfTheBenchmarkDirectory() throws Exception {
        Path dir = Files.createDirectories(root.resolve("Bench.run-Throughput"));
        Path flame = Files.writeString(dir.resolve("flame-cpu-forward.html"), "<html/>");
        Path jfr = Files.writeString(dir.resolve("jfr-cpu.jfr"), "jfr");

        ProfilerArtifacts.upload(uploaded::put, dir, STORAGE);

        assertThat(uploaded)
            .containsEntry(STORAGE.resolve("flame-cpu-forward.html"), flame)
            .containsEntry(STORAGE.resolve("jfr-cpu.jfr"), jfr)
            .hasSize(2);
    }

    @Test
    void aBenchmarkWithNoProfilerDirectoryUploadsNothingAndDoesNotFail() {
        ProfilerArtifacts.upload(uploaded::put, root.resolve("Bench.run-Throughput"), STORAGE);

        assertThat(uploaded).isEmpty();
    }
}
