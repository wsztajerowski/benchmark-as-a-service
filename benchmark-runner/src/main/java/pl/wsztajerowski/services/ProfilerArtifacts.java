package pl.wsztajerowski.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.JavaWonderlandException;
import pl.wsztajerowski.infra.StorageService;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Uploads everything a profiler wrote below its output root, each file at its path relative to that
 * root, shared by the {@code -prof} and async-profiler services.
 *
 * <p>It walks rather than naming one directory per result. The runner used to predict each
 * benchmark's directory as {@code <benchmark>-<Mode>}, but the name is chosen by the JMH inside the
 * benchmark JAR — {@code BenchmarkParams.id()}, which appends every {@code @Param}, URL-encoded, and
 * which async-profiler and JFR each post-process differently — so a {@code @Param} benchmark's
 * profiles were silently never uploaded (issue #115).
 *
 * <p>A profiler may write nothing at all — {@code gc}, {@code comp} and {@code cl} report secondary
 * metrics only — so a missing root uploads nothing rather than failing a job whose benchmark has
 * already finished, before its measurements are stored.
 */
final class ProfilerArtifacts {

    private static final Logger logger = LoggerFactory.getLogger(ProfilerArtifacts.class);

    private ProfilerArtifacts() {
    }

    static void upload(StorageService storage, Path localRoot, Path storageRoot) {
        if (!Files.isDirectory(localRoot)) {
            logger.debug("No profiler artifacts in {}", localRoot);
            return;
        }
        for (Path file : find(localRoot)) {
            Path storagePath = storageRoot.resolve(localRoot.relativize(file).toString());
            logger.info("Saving profiler output: {}", storagePath);
            storage.saveFile(storagePath, file);
        }
    }

    private static List<Path> find(Path root) {
        List<Path> found = new ArrayList<>();
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), JobLogs.MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        found.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new JavaWonderlandException(e);
        }
        return found;
    }
}
