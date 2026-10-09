package pl.wsztajerowski.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JobLogsTest {

    private static final Path OUTPUT = Path.of("jobs/lynx-journal/20260724T120000000Z-a3f9c21b");

    @TempDir
    Path root;

    private final Map<Path, Path> uploaded = new LinkedHashMap<>();

    private void upload() {
        JobLogs.upload(uploaded::put, OUTPUT, root);
    }

    private Path write(String relative) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, relative);
    }

    @Test
    void sameNamedLogsInDifferentDirectoriesKeepSeparateKeys() throws Exception {
        Path a = write("a/run.log");
        Path b = write("b/run.log");

        upload();

        assertThat(uploaded)
            .containsEntry(OUTPUT.resolve("logs/a/run.log"), a)
            .containsEntry(OUTPUT.resolve("logs/b/run.log"), b)
            .hasSize(2);
    }

    @Test
    void uploadsOnlyLogFiles() throws Exception {
        write("gc.log");
        write("benchmark.jar");
        write("catalog");

        upload();

        assertThat(uploaded).containsOnlyKeys(OUTPUT.resolve("logs/gc.log"));
    }

    @Test
    void stopsAtTheDepthCap() throws Exception {
        write("1/2/3/4/5/6/7/at-depth-eight.log");
        write("1/2/3/4/5/6/7/8/below-the-cap.log");

        upload();

        assertThat(uploaded).containsOnlyKeys(OUTPUT.resolve("logs/1/2/3/4/5/6/7/at-depth-eight.log"));
    }

    /** The /proc failure: one entry the walk cannot enter used to abort every upload after it. */
    @Test
    void anUnreadableDirectoryIsSkippedRatherThanAbortingTheWalk() throws Exception {
        write("locked/hidden.log");
        write("open/visible.log");
        Path locked = root.resolve("locked");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            upload();
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }

        assertThat(uploaded).containsKey(OUTPUT.resolve("logs/open/visible.log"));
    }
}
