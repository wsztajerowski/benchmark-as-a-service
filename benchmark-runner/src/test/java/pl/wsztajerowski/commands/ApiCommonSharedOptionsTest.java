package pl.wsztajerowski.commands;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import pl.wsztajerowski.baas.model.JobId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiCommonSharedOptionsTest {

    @Test
    void parsesTheResultsTableAndProject() {
        var options = parse("--results-table", "baas-abc-results", "--project", "lynx-journal");

        assertThat(options.resultsTableName).isEqualTo("baas-abc-results");
        assertThat(options.getProject()).isEqualTo("lynx-journal");
    }

    /** Every job names a store; the option that discarded measurements is gone. */
    @Test
    void theDiscardOptionIsUnknown() {
        assertThatThrownBy(() -> parse("--no-database", "--project", "p"))
            .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
    }

    @Test
    void aJobWithoutAResultsTableIsRefusedBeforeItRuns() {
        assertThatThrownBy(() -> parseAsGiven("--project", "p", "--s3-bucket", "b"))
            .isInstanceOf(CommandLine.MissingParameterException.class)
            .hasMessageContaining("--results-table");
    }

    /** No local-disk mode: a job's artifacts go to its bucket or the job does not start. */
    @Test
    void aJobWithoutABucketIsRefusedBeforeItRuns() {
        assertThatThrownBy(() -> parseAsGiven("--project", "p", "--results-table", "t"))
            .isInstanceOf(CommandLine.MissingParameterException.class)
            .hasMessageContaining("--s3-bucket");
    }

    /** The MongoDB store is gone, and its options with it: -m is deliberately left unassigned. */
    @Test
    void theMongoOptionsAreUnknown() {
        assertThatThrownBy(() -> parse("--mongo-connection-string", "mongodb://h:27017/db", "--project", "p"))
            .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
        assertThatThrownBy(() -> parse("-m", "mongodb://h:27017/db", "--project", "p"))
            .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
    }

    @Test
    void fallsBackToTheProjectTagSoBaasRunKeepsWorking() {
        assertThat(parse("--tag", "project=lynx-journal").getProject()).isEqualTo("lynx-journal");
    }

    @Test
    void anUnresolvedProjectIsRejectedRatherThanDefaulted() {
        assertThatThrownBy(() -> parse("--results-table", "t").getProject())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("--project");
    }

    @Test
    void createdAtIsTheCallerSuppliedInstant() {
        assertThat(parse("--created-at", "2026-08-20T17:44:32.812Z", "--project", "p").getCreatedAt())
            .isEqualTo(Instant.parse("2026-08-20T17:44:32.812Z"));
    }

    @Test
    void createdAtFallsBackToNowSoDirectInvocationKeepsWorking() {
        Instant before = Instant.now();
        Instant resolved = parse("--project", "p").getCreatedAt();
        assertThat(resolved).isBetween(before, Instant.now());
    }

    @Test
    void createdAtIsStableAcrossCallsWithinOneJob() {
        var options = parse("--project", "p");
        assertThat(options.getCreatedAt()).isEqualTo(options.getCreatedAt());
    }

    @Test
    void theDefaultJobIdIsAJobIdMintedFromTheJobsInstant() {
        var options = parse("--project", "p", "--created-at", "2026-08-20T17:44:32.812Z");

        assertThat(options.getRequestOptions().jobId())
            .hasSize(JobId.LENGTH)
            .startsWith("20260820T174432812Z-");
    }

    @Test
    void theDefaultResultPathIsTheUnifiedJobPrefix() {
        var options = parse("--project", "lynx-journal", "--created-at", "2026-08-20T17:44:32.812Z");
        var requestOptions = options.getRequestOptions();

        assertThat(requestOptions.resultPath().toString())
            .isEqualTo("jobs/lynx-journal/" + requestOptions.jobId());
    }

    @Test
    void anExplicitResultPathStaysAnOverride() {
        var options = parse("--project", "p", "--result-path", "legacy/jmh/20260101_101010");

        assertThat(options.getRequestOptions().resultPath().toString())
            .isEqualTo("legacy/jmh/20260101_101010");
    }

    @Test
    void theJobsInstantReachesTheSharedOptions() {
        var options = parse("--project", "p", "--created-at", "2026-08-20T17:44:32.812Z");

        assertThat(options.getRequestOptions().createdAt())
            .isEqualTo(Instant.parse("2026-08-20T17:44:32.812Z"));
    }

    @Test
    void anExplicitProjectWinsOverTheTag() {
        var options = parse("--tag", "project=from-tag", "--project", "explicit");

        assertThat(options.getProject()).isEqualTo("explicit");
    }

    /** An image from before JDK selection: no --jdk, and the benchmark runs on java from PATH. */
    @Test
    void noJdkMeansJavaFromPath() {
        assertThat(parse("--project", "p").javaExecutable()).isEqualTo("java");
    }

    @Test
    void aSelectedJdkIsItsJavaUnderTheRoot(@TempDir Path root) throws IOException {
        Path java = installJdk(root, "temurin-26");

        assertThat(parse("--project", "p", "--jdk", "temurin-26", "--jdks-root", root.toString()).javaExecutable())
            .isEqualTo(java.toString());
    }

    @Test
    void theRootDefaultsToTheImagesJdkDirectory() {
        assertThat(parse("--project", "p").jdksRoot).isEqualTo(Path.of("/opt/jdks"));
    }

    /** The CLI checked the image's list; a JDK missing anyway fails before the benchmark, naming what is there. */
    @Test
    void aJdkNotOnTheImageIsRefusedNamingTheOnesThatAre(@TempDir Path root) throws IOException {
        installJdk(root, "corretto-25");
        installJdk(root, "temurin-26");
        var options = parse("--project", "p", "--jdk", "temruin-26", "--jdks-root", root.toString());

        assertThatThrownBy(options::javaExecutable)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("temruin-26")
            .hasMessageContaining("corretto-25, temurin-26");
    }

    /** An id is a directory name below the root, never a path out of it. */
    @Test
    void anIdThatWouldLeaveTheRootIsRefused(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectories(dir.resolve("jdks"));
        installJdk(dir, "outside");
        var options = parse("--project", "p", "--jdk", "../outside", "--jdks-root", root.toString());

        assertThatThrownBy(options::javaExecutable).isInstanceOf(IllegalStateException.class);
    }

    private static Path installJdk(Path root, String id) throws IOException {
        Path java = Files.createDirectories(root.resolve(id).resolve("bin")).resolve("java");
        Files.createFile(java, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")));
        return java;
    }

    /** Parses with the options every job must name, unless the arguments name them already. */
    private static ApiCommonSharedOptions parse(String... args) {
        var argv = new ArrayList<>(List.of(args));
        if (!argv.contains("--results-table")) {
            argv.addAll(List.of("--results-table", "t"));
        }
        if (!argv.contains("--s3-bucket")) {
            argv.addAll(List.of("--s3-bucket", "b"));
        }
        return parseAsGiven(argv.toArray(String[]::new));
    }

    private static ApiCommonSharedOptions parseAsGiven(String... args) {
        var options = new ApiCommonSharedOptions();
        new CommandLine(options).parseArgs(args);
        return options;
    }
}
