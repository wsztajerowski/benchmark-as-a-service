package pl.wsztajerowski;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The runner's packages layer one way: {@code commands} (picocli, the composition of each benchmark
 * type) on top, imported by nothing; services, results, process and infra below it; and the root
 * package's helpers at the bottom, importing none of them. This is what keeps the package graph
 * acyclic — a parser placed in {@code commands} and a working directory anchored on
 * {@code TestWrapper} each closed a cycle once.
 */
class RunnerLayeringTest {

    private static final Path SOURCES = Path.of("src/main/java/pl/wsztajerowski");

    private static List<Path> javaFiles() {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            return files.filter(f -> f.toString().endsWith(".java")).toList();
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
    void nothingBelowTheCommandsImportsThem() {
        List<String> offenders = javaFiles().stream()
            .filter(f -> !SOURCES.relativize(f).startsWith("commands"))
            .filter(f -> read(f).contains("\nimport pl.wsztajerowski.commands.")
                || read(f).contains("\nimport static pl.wsztajerowski.commands."))
            .map(f -> SOURCES.relativize(f).toString()).toList();
        assertThat(offenders).isEmpty();
    }

    @Test
    void theRootHelpersImportNoRunnerPackage() {
        List<String> offenders = javaFiles().stream()
            .filter(f -> SOURCES.relativize(f).getNameCount() == 1)
            .filter(f -> read(f).lines().anyMatch(l -> l.matches("import (static )?pl\\.wsztajerowski\\.(?!baas\\.)[a-z]+\\..*")))
            .map(f -> SOURCES.relativize(f).toString()).toList();
        assertThat(offenders).isEmpty();
    }
}
