package pl.wsztajerowski.baas.deployment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.wsztajerowski.baas.cli.CommandRefusal;
import pl.wsztajerowski.baas.image.RunnerImageExtension;
import pl.wsztajerowski.baas.image.RunnerImageParameters;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeploymentExportTest {

    private static final String D = "baas-123456789012";
    private static final String JOB = "jobs/p/20261023T090642860Z-5165200d/";

    @TempDir
    Path tmp;

    private final FakeExportAws aws = new FakeExportAws();
    private Path dir;

    @BeforeEach
    void deployment() {
        dir = tmp.resolve("bundle");
        aws.table.add(FakeExportAws.measurement("p", "com.example.Bench#m#thrpt#2026-10-23T09:06:42.860Z#j"));
        aws.table.add(FakeExportAws.job("2026-10-23T09:06:42.860Z#j"));
        aws.bucket.put(JOB + "environment.json", "{}".getBytes(StandardCharsets.UTF_8));
        aws.bucket.put(JOB + "jmh-result.json", "[1,2,3]".getBytes(StandardCharsets.UTF_8));
        aws.bucket.put(JOB + "input/benchmark.jar", new byte[64]);
        aws.bucket.put("releases/7.2.0/benchmark-runner.jar", new byte[32]);
        aws.bucket.put("image-builds/log.txt", new byte[8]);
        aws.parameters.put(RunnerImageParameters.EXTENSION_DATA, "name: ext\nphases: []");
    }

    private DeploymentExport.Outcome export(Set<Part> parts, boolean withoutInputs) throws Exception {
        return new DeploymentExport(aws, D, "us-east-1", () -> Instant.parse("2026-10-23T12:00:00Z"))
            .run(dir, parts, withoutInputs);
    }

    @Test
    void everythingByDefault() throws Exception {
        var outcome = export(EnumSet.allOf(Part.class), false);

        var m = outcome.manifest();
        assertThat(m.parts()).containsExactly("results", "recordings", "extension");
        assertThat(m.items()).isEqualTo(2);
        assertThat(m.measurementItems()).isEqualTo(1);
        assertThat(m.jobItems()).isEqualTo(1);
        assertThat(m.objects()).isEqualTo(3);
        assertThat(m.bytes()).isEqualTo(2 + 7 + 64);
        assertThat(m.deployment()).isEqualTo(D);
        assertThat(m.account()).isEqualTo("123456789012");
        assertThat(m.region()).isEqualTo("us-east-1");
        assertThat(m.extensionHash()).isEqualTo(RunnerImageExtension.hash("name: ext\nphases: []"));

        assertThat(Files.readAllLines(dir.resolve("results.jsonl"))).hasSize(2);
        assertThat(dir.resolve("recordings/" + JOB + "jmh-result.json")).hasContent("[1,2,3]");
        assertThat(dir.resolve("runner-image-extension.yaml")).content()
            .startsWith(RunnerImageExtension.MARKER_PREFIX).contains("name: ext");
        assertThat(new Bundle(dir).readManifest()).contains(m);
    }

    @Test
    void theResultsLineIsTheStoredItem() throws Exception {
        export(EnumSet.of(Part.RESULTS), false);

        var lines = Files.readAllLines(dir.resolve("results.jsonl"));
        assertThat(lines.stream().map(ItemJson::decode)).containsExactlyInAnyOrderElementsOf(aws.table);
    }

    @Test
    void onePartAloneTouchesNothingElse() throws Exception {
        var outcome = export(EnumSet.of(Part.RESULTS), false);

        assertThat(outcome.manifest().parts()).containsExactly("results");
        assertThat(aws.calls).noneMatch(c -> c.startsWith("list") || c.startsWith("download") || c.startsWith("stackParameters"));
        assertThat(dir.resolve("recordings")).doesNotExist();
        assertThat(dir.resolve("runner-image-extension.yaml")).doesNotExist();
    }

    @Test
    void inputsCanBeLeftOut() throws Exception {
        var outcome = export(EnumSet.of(Part.RECORDINGS), true);

        assertThat(aws.calls).doesNotContain("download " + JOB + "input/benchmark.jar")
            .contains("download " + JOB + "environment.json");
        assertThat(outcome.manifest().withoutInputs()).isTrue();
        assertThat(outcome.manifest().objects()).isEqualTo(2);
    }

    @Test
    void pinnedRunnersAndBuildLogsAreNeverExported() throws Exception {
        export(EnumSet.allOf(Part.class), false);

        assertThat(aws.calls).noneMatch(c -> c.contains("releases/") || c.contains("image-builds/"));
        try (Stream<Path> files = Files.walk(dir)) {
            assertThat(files.map(Path::toString)).noneMatch(p -> p.contains("releases") || p.contains("image-builds"));
        }
    }

    @Test
    void noExtensionDeployed() throws Exception {
        aws.parameters.put(RunnerImageParameters.EXTENSION_DATA, "# comments only\n");

        var outcome = export(EnumSet.of(Part.EXTENSION), false);

        assertThat(outcome.manifest().extensionHash()).isNull();
        assertThat(outcome.manifest().parts()).containsExactly("extension");
        assertThat(dir.resolve("runner-image-extension.yaml")).doesNotExist();
    }

    @Test
    void aNonEmptyDirectoryIsRefusedBeforeAnyAwsCall() throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("old.txt"), "x");

        assertThatThrownBy(() -> export(EnumSet.allOf(Part.class), false))
            .isInstanceOf(CommandRefusal.class).hasMessageContaining("not empty");
        assertThat(aws.calls).isEmpty();
    }

    @Test
    void aKeyEscapingTheBundleIsRefusedAndNothingIsDownloaded() throws Exception {
        aws.bucket.put("jobs/../../../escaped.txt", new byte[1]);

        assertThatThrownBy(() -> export(EnumSet.of(Part.RECORDINGS), false))
            .isInstanceOf(CommandRefusal.class).hasMessageContaining("jobs/../../../escaped.txt");
        assertThat(aws.calls).noneMatch(c -> c.startsWith("download"));
        assertThat(tmp.resolve("escaped.txt")).doesNotExist();
        assertThat(dir.resolve("manifest.json")).doesNotExist();
    }

    @Test
    void aSizeMismatchFailsTheExportWithoutAManifest() throws Exception {
        aws.truncated.add(JOB + "input/benchmark.jar");

        assertThatThrownBy(() -> export(EnumSet.allOf(Part.class), false))
            .isInstanceOf(CommandRefusal.class).hasMessageContaining(JOB + "input/benchmark.jar");
        assertThat(dir.resolve("manifest.json")).doesNotExist();
    }

    /** What an interrupt mid-download leaves: the results written, and no manifest. */
    @Test
    void aFailedDownloadLeavesNoManifest() throws Exception {
        aws.failDownload = new IllegalStateException("connection reset");

        assertThatThrownBy(() -> export(EnumSet.allOf(Part.class), false)).hasMessageContaining("connection reset");
        assertThat(dir.resolve("results.jsonl")).exists();
        assertThat(dir.resolve("manifest.json")).doesNotExist();
    }

    @Test
    void aRefusedScanNamesTheActionAndSetup() {
        aws.failScan = (DynamoDbException) DynamoDbException.builder()
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDeniedException")
                .errorMessage("User is not authorized to perform: dynamodb:Scan").build())
            .statusCode(400).message("not authorized").build();

        assertThatThrownBy(() -> export(EnumSet.of(Part.RESULTS), false))
            .isInstanceOf(CommandRefusal.class)
            .hasMessageContaining("dynamodb:Scan")
            .hasMessageContaining("--deployment " + D + " admin deployment setup");
    }

    @Test
    void theDefaultDirectoryNamesTheDeploymentAndTheInstant() {
        assertThat(DeploymentExport.defaultDirectory(D, Instant.parse("2026-10-23T12:34:56.789Z")))
            .isEqualTo(Path.of("baas-export-" + D + "-20261023T123456Z"));
    }

    @Test
    void inputKeysAreRecognisedByTheirSegment() {
        assertThat(DeploymentExport.isInput(JOB + "input/benchmark.jar")).isTrue();
        assertThat(DeploymentExport.isInput(JOB + "logs/input/x.log")).isFalse();
        assertThat(DeploymentExport.isInput("jobs/input/j/x")).isFalse();
    }

    @Test
    void anUnknownPartIsAUsageError() {
        assertThatThrownBy(() -> Part.parse("results,tables"))
            .isInstanceOf(CommandRefusal.class)
            .hasMessageContaining("tables").hasMessageContaining("results, recordings, extension")
            .satisfies(e -> assertThat(((CommandRefusal) e).exitCode()).isEqualTo(2));
    }
}
