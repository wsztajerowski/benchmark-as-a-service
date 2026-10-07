package pl.wsztajerowski.baas.commands.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import pl.wsztajerowski.baas.BaasApp;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.infra.RunnerImageExtension;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The command-side edges of the runner-image extension. The guard, the size limit and the plan are
 * unit-tested where they live (RunnerImageExtensionTest, RunnerImageParametersTest); what a command
 * does with AWS is exercised end to end by the change's manual checks.
 */
class RunnerImageExtensionCommandsTest {

    private static final String EXTENSION = """
        name: runner-extension
        schemaVersion: 1.0
        phases:
          - name: build
            steps:
              - name: InstallOtelCollector
                action: ExecuteBash
                inputs:
                  commands:
                    - dnf install -y otelcol
        """;

    @TempDir Path dir;

    // ─── Options ─────────────────────────────────────────────────────────────────

    @Test
    void buildImageTakesAnExtensionFile() {
        var result = new CommandLine(new BaasApp()).parseArgs("admin", "image", "build", "--extension", "ext.yaml");

        assertThat(((ImageBuildSubcommand) result.subcommand().subcommand().subcommand().commandSpec().userObject()).extensionFile)
            .isEqualTo(Path.of("ext.yaml"));
    }

    @Test
    void imageTakesTheExtensionFlag() {
        var result = new CommandLine(new BaasApp()).parseArgs("admin", "image", "show", "--extension");

        assertThat(((ImageShowSubcommand) result.subcommand().subcommand().subcommand().commandSpec().userObject()).printExtension)
            .isTrue();
    }

    /**
     * Refused from the file alone, before a client is built: the configured profile does not exist,
     * so reaching AWS would fail with a credentials error rather than this message.
     */
    @Test
    void anOversizedExtensionIsRefusedBeforeAnythingIsSubmitted() throws Exception {
        pl.wsztajerowski.baas.TestDeployments.writeRaw(dir, "baas-123456789012", """
            prefix: "baas-123456789012"
            aws:
              profile: "no-such-profile-baas-test"
              region: "eu-central-1"
            """);
        Path big = dir.resolve("big.yaml");
        Files.writeString(big, "name: big\n#" + "x".repeat(5000) + "\n");

        var stderr = new ByteArrayOutputStream();
        PrintStream original = System.err;
        int exit;
        try {
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
            exit = new CommandLine(new BaasApp(dir)).execute(
                "admin", "image", "build", "--extension", big.toString());
        } finally {
            System.setErr(original);
        }

        assertThat(exit).isEqualTo(1);
        assertThat(stderr.toString(StandardCharsets.UTF_8))
            .contains("caps at 4096")
            .doesNotContain("no-such-profile-baas-test");
    }

    // ─── Setup's starter ─────────────────────────────────────────────────────────

    @Test
    void setupWritesTheStarterWhenNoneExists() throws Exception {
        Path file = dir.resolve(SetupCommand.EXTENSION_STARTER_FILE);

        SetupCommand.writeExtensionStarter(file);

        assertThat(Files.readString(file))
            .as("the same document a pull of a deployment with no extension prints")
            .isEqualTo(RunnerImageExtension.withMarker(""))
            .startsWith("# baas-extension-base: none");
        assertThat(RunnerImageExtension.parse(Files.readString(file)).content())
            .as("pushed unchanged, the starter deploys nothing")
            .isEmpty();
    }

    @Test
    void setupNeverOverwritesAnEditedExtensionFile() throws Exception {
        Path file = dir.resolve(SetupCommand.EXTENSION_STARTER_FILE);
        String edited = "# baas-extension-base: none\n" + EXTENSION;
        Files.writeString(file, edited);

        SetupCommand.writeExtensionStarter(file);

        assertThat(Files.readString(file)).isEqualTo(edited);
    }

    // ─── `baas admin image show` ──────────────────────────────────────────────────────

    @Test
    void theReportNamesTheExtensionItsSizeAndItsSteps() {
        String hash = RunnerImageExtension.hash(EXTENSION);

        String report = report(EXTENSION, "1.3.0+ext." + hash);

        assertThat(report)
            .contains("Extension:   " + hash + ", " + EXTENSION.length() + " bytes of 4096")
            .contains("build: InstallOtelCollector")
            .doesNotContain("not in the published image");
    }

    @Test
    void anExtensionNotYetBakedIsSaidToBe() {
        assertThat(report(EXTENSION, "1.3.0"))
            .as("a push whose build failed leaves the stack ahead of the published image")
            .contains("(deployed; not in the published image yet)");
    }

    @Test
    void noExtensionIsReportedAsNone() {
        assertThat(report("", "1.3.0")).contains("Extension:   none");
    }

    @Test
    void theBaseVersionIsReadFromTheLabel() {
        assertThat(ImageShowSubcommand.baseOf("1.3.0+ext.3f9a1c2e")).isEqualTo("1.3.0");
        assertThat(ImageShowSubcommand.baseOf("1.2.0")).isEqualTo("1.2.0");
        assertThat(ImageShowSubcommand.baseOf(null)).isNull();
    }

    private static String report(String extension, String label) {
        var out = new StringWriter();
        ImageShowSubcommand.printExtension(Console.plain(new PrintWriter(out, true)), extension, label);
        return out.toString();
    }
}
