package pl.wsztajerowski.baas.commands;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An S3 key is an arbitrary string, and anything holding {@code s3:PutObject} on the bucket — the
 * runner role included, so any code in a benchmark JAR — can write one under a job's prefix. None of
 * them may decide where {@code baas download} writes on the operator's machine (finding S13).
 */
class DownloadDestinationTest {

    private static final String PREFIX = "jobs/p/20261004T101500000Z-0a1b2c3d/";
    private final Path root = Path.of("out").toAbsolutePath();

    @Test
    void aKeyLandsAtItsRelativePathUnderTheRoot() {
        assertThat(JobsDownloadSubcommand.destinationFor(root, PREFIX, PREFIX + "logs/a/run.log"))
            .contains(root.resolve("logs/a/run.log"));
    }

    @Test
    void aKeyClimbingOutOfTheRootIsRefused() {
        assertThat(JobsDownloadSubcommand.destinationFor(root, PREFIX, PREFIX + "../../.zshrc")).isEmpty();
    }

    @Test
    void aKeyThatOnlyLooksLikeItClimbsIsKept() {
        assertThat(JobsDownloadSubcommand.destinationFor(root, PREFIX, PREFIX + "logs/../environment.json"))
            .contains(root.resolve("environment.json"));
    }

    /** {@code Path.resolve} returns an absolute argument as it is, which would discard the root. */
    @Test
    void aKeyWhoseRemainderIsAbsoluteIsRefused() {
        assertThat(JobsDownloadSubcommand.destinationFor(root, PREFIX, PREFIX + "/etc/passwd")).isEmpty();
    }

    @Test
    void aKeyNamingTheRootItselfIsRefused() {
        assertThat(JobsDownloadSubcommand.destinationFor(root, PREFIX, PREFIX + "a/..")).isEmpty();
    }

    @Test
    void theDefaultDirectoryIsTheJobsLastSegment() {
        assertThat(JobsDownloadSubcommand.defaultRoot("jobs/p/20261004T101500000Z-0a1b2c3d"))
            .isEqualTo(Path.of("20261004T101500000Z-0a1b2c3d"));
    }

    /** A literal path ending in {@code ..} would otherwise make the parent directory the default. */
    @Test
    void aLiteralPathEndingInADotSegmentHasNoDefaultDirectory() {
        assertThatThrownBy(() -> JobsDownloadSubcommand.defaultRoot("jobs/p/.."))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("--output-dir");
        assertThatThrownBy(() -> JobsDownloadSubcommand.defaultRoot("jobs/p/."))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
