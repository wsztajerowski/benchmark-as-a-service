package pl.wsztajerowski.baas.commands;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import pl.wsztajerowski.baas.BaasApp;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Refusals that must happen before any AWS call, driven through the real command tree. Every
 * command here would reach AWS next, and the test machine has no credentials for it, so an exit
 * code and a message about the option — not about AWS — prove the order.
 */
class OptionValidationTest {

    @TempDir
    Path dir;

    record Captured(String out, String err, int exitCode) {}

    private Captured baas(String... args) throws Exception {
        Path config = dir.resolve("config.yaml");
        if (!Files.exists(config)) {
            Files.writeString(config, "prefix: \"baas-123456789012\"\naws:\n  region: \"eu-central-1\"\n");
        }
        var out = new StringWriter();
        var err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            String[] withConfig = new String[args.length + 2];
            withConfig[0] = "--config-path";
            withConfig[1] = config.toString();
            System.arraycopy(args, 0, withConfig, 2, args.length);
            int code = new CommandLine(new BaasApp()).setOut(new PrintWriter(out)).execute(withConfig);
            return new Captured(out.toString(), err.toString(StandardCharsets.UTF_8), code);
        } finally {
            System.setErr(originalErr);
        }
    }

    /** U25: refused before the JAR is even looked at, so long before anything is uploaded. */
    @Test
    void aReservedTagIsRefusedBeforeTheJarOrAnyUpload() throws Exception {
        var captured = baas("run", "--runner-jar", "/nonexistent-runner.jar",
            "--benchmark-jar", "/nonexistent.jar", "--project", "p", "--tag", "jdk=21", "jmh");

        assertThat(captured.exitCode()).isNotZero();
        assertThat(captured.err()).contains("--tag jdk").doesNotContain("Benchmark JAR not found");
    }

    @Test
    void aProjectTagIsRefusedBeforeTheJarOrAnyUpload() throws Exception {
        var captured = baas("run", "--runner-jar", "/nonexistent-runner.jar",
            "--benchmark-jar", "/nonexistent.jar", "--project", "p", "--tag", "project=q", "jmh");

        assertThat(captured.exitCode()).isNotZero();
        assertThat(captured.err()).contains("--tag project").doesNotContain("Benchmark JAR not found");
    }

    /** U26: `timeout 0` disables the process timeout on the instance. */
    @Test
    void aZeroTimeoutIsRefusedOnRun() throws Exception {
        var captured = baas("run", "--runner-jar", "/nonexistent-runner.jar",
            "--benchmark-jar", "/nonexistent.jar", "--project", "p", "--timeout", "0", "jmh");

        assertThat(captured.exitCode()).isNotZero();
        assertThat(captured.err()).contains("timeout must be at least 1");
    }

    @Test
    void aZeroOrNegativeTimeoutIsRefusedByConfigSet() throws Exception {
        assertThat(baas("config", "set", "--timeout", "0").exitCode()).isEqualTo(2);
        assertThat(baas("config", "set", "--timeout", "-5").exitCode()).isEqualTo(2);
        assertThat(Files.readString(dir.resolve("config.yaml"))).doesNotContain("benchmarkTimeoutSeconds");
    }

    /** U35: an unknown type is a usage error, like an unknown --format. */
    @Test
    void anUnknownRunTypeIsAUsageError() throws Exception {
        assertThat(baas("run", "--benchmark-jar", "/x.jar", "not-a-type").exitCode()).isEqualTo(2);
    }

    @Test
    void aNegativeLimitOrOffsetIsRefused() throws Exception {
        assertThat(baas("query", "--all-projects", "--limit", "-1").exitCode()).isEqualTo(2);
        assertThat(baas("query", "--all-projects", "--offset", "-1").exitCode()).isEqualTo(2);
    }

    @Test
    void jobIdRefusesOnlyTheOptionsThatChooseAPartition() throws Exception {
        var project = baas("query", "--job-id", "20260820T174432812Z-a3f9c21b", "--project", "p");
        assertThat(project.exitCode()).isEqualTo(2);
        assertThat(project.err()).contains("--project");

        var all = baas("query", "--job-id", "20260820T174432812Z-a3f9c21b", "--all-projects");
        assertThat(all.exitCode()).isEqualTo(2);
        assertThat(all.err()).contains("--all-projects");
    }

    @Test
    void theRemovedResultsOptionsAreUnknown() throws Exception {
        for (String removed : new String[]{"--all-jobs", "--group-by=branch", "--living-branches", "--all"}) {
            assertThat(baas("query", "--all-projects", removed).exitCode()).as(removed).isEqualTo(2);
        }
    }

    @Test
    void anUnknownSortFieldIsRefused() throws Exception {
        var captured = baas("query", "--all-projects", "--sort-by", "colour");

        assertThat(captured.exitCode()).isEqualTo(2);
        assertThat(captured.err()).contains("created");
    }

    @Test
    void aMalformedExcludedTagIsRefused() throws Exception {
        assertThat(baas("query", "--all-projects", "--exclude-tag", "branch").exitCode()).isEqualTo(2);
    }

    /** U41: the bare NoSuchFileException message was only the path. */
    @Test
    void aMissingExtensionFileIsNamedAsMissing() throws Exception {
        var captured = baas("admin", "image", "build", "--extension", dir.resolve("ext.yml").toString());

        assertThat(captured.exitCode()).isEqualTo(1);
        assertThat(captured.err()).contains("Extension file not found");
    }

    /** U29: job items made every job since resolvable by id, failed ones included. */
    @Test
    void envDiffNoLongerSaysAFailedJobNeedsItsPath() {
        String help = new CommandLine(new BaasApp()).getSubcommands().get("jobs")
            .getSubcommands().get("diff").getUsageMessage();

        assertThat(help).doesNotContain("has no index entry");
    }
}
