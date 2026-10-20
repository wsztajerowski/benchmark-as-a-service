package pl.wsztajerowski.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.JavaWonderlandException;
import pl.wsztajerowski.infra.StorageService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Uploads one benchmark's profiler directory, shared by the {@code -prof} and async-profiler
 * services. A profiler may write no directory for a benchmark — {@code gc}, {@code comp} and
 * {@code cl} report secondary metrics only, and async-profiler skips a benchmark it produced no
 * samples for — and listing a missing one failed the job after the benchmark had finished, before
 * its measurements were stored.
 */
final class ProfilerArtifacts {

    private static final Logger logger = LoggerFactory.getLogger(ProfilerArtifacts.class);

    private ProfilerArtifacts() {
    }

    static void upload(StorageService storage, Path localDir, Path storageDir) {
        if (!Files.isDirectory(localDir)) {
            logger.debug("No profiler artifacts in {}", localDir);
            return;
        }
        try (Stream<Path> paths = Files.list(localDir)) {
            paths.forEach(path -> {
                Path storagePath = storageDir.resolve(path.getFileName());
                logger.info("Saving profiler output: {}", storagePath);
                storage.saveFile(storagePath, path);
            });
        } catch (IOException e) {
            throw new JavaWonderlandException(e);
        }
    }
}
