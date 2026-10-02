package pl.wsztajerowski.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wsztajerowski.infra.StorageService;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Ships every {@code *.log} below the working directory to {@code <result-path>/logs/} — for every
 * benchmark type. It lived in the jmh service alone, so the profiler modes and jcstress silently
 * left a benchmark's own GC log on an instance about to terminate.
 *
 * <p>The walk no longer relies on user-data's {@code cd /app} to be safe. An entry that vanishes or
 * cannot be read is skipped rather than aborting the walk (the {@code /proc} failure), the depth is
 * capped, and the key keeps the path relative to the root, so {@code a/run.log} and
 * {@code b/run.log} no longer overwrite each other. Files are collected before any is uploaded,
 * because {@code LocalStorageService} writes below the same working directory the walk reads.
 */
final class RunLogs {
    private static final Logger logger = LoggerFactory.getLogger(RunLogs.class);

    static final int MAX_DEPTH = 8;

    private RunLogs() {}

    static void upload(StorageService storageService, Path outputPath) {
        upload(storageService, outputPath, Paths.get(""));
    }

    static void upload(StorageService storageService, Path outputPath, Path root) {
        Path start = root.toAbsolutePath().normalize();
        for (Path file : find(start)) {
            Path relative = start.relativize(file);
            logger.info("Saving log file: {}", relative);
            storageService.saveFile(outputPath.resolve("logs").resolve(relative), file);
        }
    }

    private static List<Path> find(Path start) {
        List<Path> found = new ArrayList<>();
        try {
            Files.walkFileTree(start, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && file.getFileName().toString().endsWith(".log")) {
                        found.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    logger.debug("Skipping unreadable entry {}: {}", file, e.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // Logs are a diagnostic extra; failing to list them must not fail a measured run.
            logger.warn("Could not scan {} for log files: {}", start, e.getMessage());
        }
        return found;
    }
}
