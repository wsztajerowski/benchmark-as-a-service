package pl.wsztajerowski.baas.model;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code model} package is the contract every runtime reads — the CLI, the runner and, through
 * values rendered into user-data, the instance's shell. It stays free of the AWS SDK so that a
 * runtime with no DynamoDB in it could still depend on it; the table encoding lives in
 * {@code model.dynamodb}.
 */
class ModelBoundaryTest {

    @Test
    void theModelPackageImportsNoAwsSdk() throws IOException {
        Path dir = Path.of("src/main/java/pl/wsztajerowski/baas/model");
        try (Stream<Path> files = Files.list(dir)) {
            List<String> offenders = files
                .filter(f -> f.toString().endsWith(".java"))
                .filter(f -> read(f).lines().anyMatch(l -> l.startsWith("import software.amazon")))
                .map(f -> f.getFileName().toString())
                .toList();
            assertThat(offenders).isEmpty();
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
