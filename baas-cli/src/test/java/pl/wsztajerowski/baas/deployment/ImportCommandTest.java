package pl.wsztajerowski.baas.deployment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import pl.wsztajerowski.baas.BaasApp;
import pl.wsztajerowski.baas.TestDeployments;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.image.RunnerImageExtension;
import pl.wsztajerowski.baas.image.RunnerImageParameters;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ImportCommandTest {

    private static final String SOURCE = "baas-111111111111";
    private static final String TARGET = TestDeployments.DEFAULT;
    private static final String JOB = "jobs/p/20261023T090642860Z-5165200d/";
    private static final String EXTENSION = "name: ext\nphases: []";

    @TempDir
    Path dir;

    private final FakeExportAws source = new FakeExportAws();
    private final FakeExportAws target = new FakeExportAws();
    private final StringWriter out = new StringWriter();
    private Path bundle;
    private boolean interactive = true;
    private String answer = TARGET;

    @BeforeEach
    void exportASource() throws Exception {
        source.table.add(FakeExportAws.measurement("p", "com.example.Bench#m#thrpt#2026-10-23T09:06:42.860Z#j"));
        source.table.add(FakeExportAws.job("2026-10-23T09:06:42.860Z#j"));
        source.bucket.put(JOB + "environment.json", "{}".getBytes(StandardCharsets.UTF_8));
        source.bucket.put(JOB + "input/benchmark.jar", new byte[64]);
        source.parameters.put(RunnerImageParameters.EXTENSION_DATA, EXTENSION);
        bundle = dir.resolve("bundle");
        export(EnumSet.allOf(Part.class), bundle);
    }

    private void export(Set<Part> parts, Path into) throws Exception {
        new DeploymentExport(source, SOURCE, "eu-central-1", () -> Instant.parse("2026-10-23T12:00:00Z"))
            .run(into, parts, false);
    }

    private int baas(String... args) throws Exception {
        TestDeployments.write(dir, TARGET);
        CommandLine.IFactory defaults = CommandLine.defaultFactory();
        CommandLine.IFactory factory = new CommandLine.IFactory() {
            @Override
            @SuppressWarnings("unchecked")
            public <K> K create(Class<K> cls) throws Exception {
                if (cls == ImportCommand.class) {
                    var command = new ImportCommand();
                    command.awsFor = config -> target;
                    command.console = Console.withFlags(new PrintWriter(out, true), interactive, false);
                    command.answerReader = () -> answer;
                    return (K) command;
                }
                return defaults.create(cls);
            }
        };
        return BaasApp.commandLine(new BaasApp(dir), factory)
            .setOut(new PrintWriter(out))
            .setErr(new PrintWriter(new StringWriter()))
            .execute(args);
    }

    private int importAll(String... extra) throws Exception {
        String[] args = new String[4 + extra.length];
        System.arraycopy(new String[]{"admin", "deployment", "import", bundle.toString()}, 0, args, 0, 4);
        System.arraycopy(extra, 0, args, 4, extra.length);
        return baas(args);
    }

    @Test
    void aFullBundleRestoresEverythingAndHandsTheExtensionOver() throws Exception {
        assertThat(importAll()).isZero();

        assertThat(target.table).containsExactlyInAnyOrderElementsOf(source.table);
        assertThat(target.bucket.keySet()).containsExactlyInAnyOrderElementsOf(source.bucket.keySet());
        assertThat(target.bucket.get(JOB + "environment.json")).isEqualTo("{}".getBytes(StandardCharsets.UTF_8));
        assertThat(out.toString())
            .contains("from  " + SOURCE + " (account 123456789012, eu-central-1)")
            .contains("into  " + TARGET)
            .contains("results     2 written, 0 already present")
            .contains("recordings  2 written, 0 already present")
            .contains("--deployment " + TARGET + " admin image build --extension " + bundle.resolve("runner-image-extension.yaml"));
        assertThat(target.calls).noneMatch(c -> c.startsWith("putObject releases/"));
    }

    @Test
    void aRerunWritesNothingAndCountsEverythingAsPresent() throws Exception {
        assertThat(importAll()).isZero();
        out.getBuffer().setLength(0);

        assertThat(importAll()).isZero();

        assertThat(out.toString()).contains("results     0 written, 2 already present", "recordings  0 written, 2 already present");
        assertThat(target.table).hasSize(2);
    }

    @Test
    void anItemTheTargetHoldsIsNeverReplaced() throws Exception {
        var newer = new java.util.HashMap<>(FakeExportAws.job("2026-10-23T09:06:42.860Z#j"));
        newer.put("status", AttributeValue.fromS("cancelled"));
        target.table.add(Map.copyOf(newer));

        assertThat(importAll()).isZero();

        assertThat(target.table).contains(Map.copyOf(newer)).hasSize(2);
        assertThat(out.toString()).contains("results     1 written, 1 already present");
    }

    @Test
    void oneSelectedPartLeavesTheOthersUntouched() throws Exception {
        assertThat(importAll("--parts", "results")).isZero();

        assertThat(target.table).hasSize(2);
        assertThat(target.bucket).isEmpty();
        assertThat(target.calls).noneMatch(c -> c.startsWith("putObject") || c.startsWith("stackParameters"));
        assertThat(out.toString()).doesNotContain("recordings", "admin image build");
    }

    @Test
    void theExtensionAloneWritesNoItemAndNoObject() throws Exception {
        assertThat(importAll("--parts", "extension")).isZero();

        assertThat(target.calls).noneMatch(c -> c.startsWith("putItem") || c.startsWith("putObject"));
        assertThat(out.toString()).contains("admin image build --extension");
    }

    @Test
    void anExtensionAlreadyDeployedPrintsNoCommand() throws Exception {
        target.parameters.put(RunnerImageParameters.EXTENSION_DATA, EXTENSION);

        assertThat(importAll("--parts", "extension")).isZero();

        assertThat(out.toString()).contains(RunnerImageExtension.hash(EXTENSION) + " is already deployed")
            .doesNotContain("admin image build");
    }

    /** The guard on the push, not import, refuses this one: import still names the command. */
    @Test
    void aDifferentDeployedExtensionStillGetsTheCommand() throws Exception {
        target.parameters.put(RunnerImageParameters.EXTENSION_DATA, "name: other\nphases: []");

        assertThat(importAll("--parts", "extension")).isZero();

        assertThat(out.toString()).contains("admin image build --extension");
        assertThat(Files.readString(bundle.resolve("runner-image-extension.yaml")))
            .startsWith(RunnerImageExtension.MARKER_PREFIX + " " + RunnerImageExtension.hash(EXTENSION));
    }

    @Test
    void aBundleWithoutAnExtensionSaysSo() throws Exception {
        source.parameters.clear();
        Path other = dir.resolve("no-extension");
        export(EnumSet.of(Part.EXTENSION), other);
        bundle = other;

        assertThat(importAll()).isZero();
        assertThat(out.toString()).contains(SOURCE + " held none; nothing to push.");
    }

    @Test
    void aWrongNameAbortsBeforeAnyWrite() throws Exception {
        answer = "baas-1234";

        assertThat(importAll()).isEqualTo(1);
        assertThat(target.calls).noneMatch(c -> c.startsWith("put"));
    }

    @Test
    void withoutATerminalNothingIsAskedOrWritten() throws Exception {
        interactive = false;

        assertThat(importAll()).isEqualTo(1);
        assertThat(target.calls).isEmpty();
    }

    @Test
    void anIncompleteBundleIsRefused() throws Exception {
        Files.delete(bundle.resolve("manifest.json"));

        assertThat(importAll()).isEqualTo(1);
        assertThat(target.calls).isEmpty();
    }

    @Test
    void aNewerSchemaIsRefused() throws Exception {
        Path manifest = bundle.resolve("manifest.json");
        Files.writeString(manifest, Files.readString(manifest).replace("\"schemaVersion\" : 1", "\"schemaVersion\" : 2"));

        assertThat(importAll()).isEqualTo(1);
        assertThat(target.calls).isEmpty();
    }

    @Test
    void aTargetThatIsNotSetUpIsRefused() throws Exception {
        target.tableExists = false;

        assertThat(importAll()).isEqualTo(1);
        assertThat(target.calls).noneMatch(c -> c.startsWith("put"));
    }

    @Test
    void aPartTheBundleLacksIsRefused() throws Exception {
        Path resultsOnly = dir.resolve("results-only");
        export(EnumSet.of(Part.RESULTS), resultsOnly);
        bundle = resultsOnly;

        assertThat(importAll("--parts", "recordings")).isEqualTo(1);
        assertThat(target.calls).isEmpty();
    }

    @Test
    void anUnknownPartIsAUsageError() throws Exception {
        assertThat(importAll("--parts", "tables")).isEqualTo(2);
        assertThat(target.calls).isEmpty();
    }

    @Test
    void aFileOutsideJobsIsRefusedBeforeAnyWrite() throws Exception {
        Path stray = bundle.resolve("recordings/releases/7.2.0/benchmark-runner.jar");
        Files.createDirectories(stray.getParent());
        Files.write(stray, new byte[4]);

        assertThat(importAll()).isEqualTo(1);
        assertThat(target.calls).noneMatch(c -> c.startsWith("put"));
    }

    @Test
    void theHelpDescribesTheParts() throws Exception {
        assertThat(baas("admin", "deployment", "import", "--help")).isZero();
        assertThat(out.toString()).contains("results", "recordings", "extension", "Nothing is overwritten", "--parts");
    }
}
