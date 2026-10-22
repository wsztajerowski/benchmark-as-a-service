package pl.wsztajerowski.baas.jobs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

class SessionPluginTest {

    @TempDir Path dir;

    private Path plugin(Path in, String permissions) throws Exception {
        Path file = Files.createDirectories(in).resolve(SessionPlugin.NAME);
        Files.writeString(file, "#!/bin/sh\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(permissions));
        return file;
    }

    @Test
    void theFirstExecutableOnThePathIsFound() throws Exception {
        Path first = plugin(dir.resolve("a"), "rwxr-xr-x");
        plugin(dir.resolve("b"), "rwxr-xr-x");

        assertThat(SessionPlugin.find(dir.resolve("a") + File.pathSeparator + dir.resolve("b"))).contains(first);
    }

    @Test
    void aFileThatCannotBeExecutedIsSkipped() throws Exception {
        plugin(dir.resolve("a"), "rw-r--r--");
        Path second = plugin(dir.resolve("b"), "rwxr-xr-x");

        assertThat(SessionPlugin.find(dir.resolve("a") + File.pathSeparator + dir.resolve("b"))).contains(second);
    }

    @Test
    void noPathFindsNothing() {
        assertThat(SessionPlugin.find(null)).isEmpty();
        assertThat(SessionPlugin.find(dir.toString())).isEmpty();
    }

    @Test
    void theInstallCommandFollowsThePlatform() {
        assertThat(SessionPlugin.missing("Mac OS X")).contains("brew install --cask session-manager-plugin");
        assertThat(SessionPlugin.missing("Linux")).contains("RPM or DEB", "session-manager-working-with-install-plugin");
    }
}
