package pl.wsztajerowski.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import pl.wsztajerowski.TestcontainersWithDynamoDbBaseIT;
import pl.wsztajerowski.commands.ApiCommonSharedOptions;
import pl.wsztajerowski.infra.S3StorageService;
import pl.wsztajerowski.options.CommonSharedOptions;
import pl.wsztajerowski.options.JmhRun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A job that selects a JDK runs the benchmark on that JDK's {@code java}, found by id under the JDK
 * root. The JDK here is the one running the tests, behind a {@code bin/java} that leaves a marker
 * and hands over to it — so the test proves which executable the runner started, on any CI host.
 * That JMH's forks and JCStress's then follow the started JVM is JMH's and JCStress's behaviour,
 * checked on Temurin in an Amazon Linux container (verify.md, 1.3).
 */
class JmhSelectedJdkIT extends TestcontainersWithDynamoDbBaseIT {

    @Test
    void theBenchmarkRunsOnTheSelectedJdk(@TempDir Path root) throws IOException {
        Path marker = root.resolve("started-by");
        Path java = Files.createDirectories(root.resolve("ci-jdk").resolve("bin")).resolve("java");
        Path realJava = Path.of(System.getProperty("java.home"), "bin", "java");
        Files.writeString(java, "#!/bin/sh\necho \"$0\" > '" + marker + "'\nexec '" + realJava + "' \"$@\"\n");
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"));
        var options = new ApiCommonSharedOptions();
        new CommandLine(options).parseArgs("--results-table", "t", "--s3-bucket", "b", "--project", "p",
            "--jdk", "ci-jdk", "--jdks-root", root.toString());

        new JmhSubcommandService(
            new S3StorageService(awsS3Client, TEST_BUCKET_NAME),
            resultsStore(),
            new CommonSharedOptions(Path.of("test-jdk"), "req-jdk", Instant.now(), "test-project", Map.of()),
            new JmhRun(Path.of("target", "fake-jmh-benchmarks.jar").toAbsolutePath(),
                List.of("Incrementing_Synchronized", "-f", "1", "-wi", "0", "-i", "1"),
                Files.createTempDirectory("jmh"), Path.of("no-async-profiler"), options.javaExecutable()))
            .executeCommand();

        assertThat(Files.readString(marker).strip()).isEqualTo(java.toString());
        assertThat(storedMeasurements()).singleElement()
            .satisfies(measurement -> assertThat(measurement.benchmarkMethod()).isEqualTo("incrementUsingSynchronized"));
    }
}
