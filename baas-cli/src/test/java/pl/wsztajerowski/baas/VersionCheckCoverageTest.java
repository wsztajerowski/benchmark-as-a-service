package pl.wsztajerowski.baas;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The version check (cli-version-floor) covers a command by construction: it lives in the two
 * sessions, so it is skipped only by building clients some other way, or by asking for the one
 * unchecked factory. This reads the sources to pin both, the way {@code SliceBoundaryTest} reads
 * imports — a new command that bypasses the sessions fails here rather than silently going
 * unchecked.
 */
class VersionCheckCoverageTest {

    private static final Path SOURCES = Path.of("src/main/java/pl/wsztajerowski/baas");

    private static List<Path> sourcesContaining(String text) {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            return files.filter(f -> f.toString().endsWith(".java"))
                .filter(f -> read(f).contains(text))
                .map(SOURCES::relativize)
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void onlyJobsTerminateAsksForTheUncheckedFactory() {
        assertThat(sourcesContaining(".uncheckedFactory()"))
            .containsExactly(Path.of("jobs/JobsTerminateSubcommand.java"));
    }

    @Test
    void clientsAreBuiltOnlyThroughTheSessions() {
        assertThat(sourcesContaining("new AwsClientFactory(")).allSatisfy(file ->
            assertThat(file.startsWith("session") || file.startsWith("aws")).as("%s", file).isTrue());
        assertThat(sourcesContaining("AwsClientFactory.deferred(")).allSatisfy(file ->
            assertThat(file.startsWith("session") || file.startsWith("aws")).as("%s", file).isTrue());
    }

    /** Setup's unchecked overload, which setup backs with its own check in the names step. */
    @Test
    void onlySetupUsesTheDeployerFactoryForANamedRegion() {
        assertThat(sourcesContaining("DeployerSession.factory(region, profile)"))
            .containsExactly(Path.of("deployment/setup/AwsSetupAws.java"));
    }
}
