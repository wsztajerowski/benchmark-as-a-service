package pl.wsztajerowski.baas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `--deployment` is the one pointer to a concrete deployment. While a machine is configured for a
 * single deployment it may only name that one, and the check runs before any command does.
 */
class DeploymentOptionTest {

    private static Path config(Path dir, String prefix) throws Exception {
        Path file = dir.resolve("config.yaml");
        Files.writeString(file, "prefix: " + prefix + "\naws:\n  region: eu-central-1\n");
        return file;
    }

    private static String refusal(String... args) {
        var app = new BaasApp();
        var parsed = new CommandLine(app).parseArgs(args);
        return app.deploymentRefusal(parsed);
    }

    @Test
    void theConfiguredDeploymentIsAccepted(@TempDir Path dir) throws Exception {
        Path file = config(dir, "baas-123456789012");

        assertThat(refusal("--config-path", file.toString(), "--deployment", "baas-123456789012",
            "jobs", "list")).isNull();
    }

    @Test
    void anotherDeploymentIsRefusedNamingBoth(@TempDir Path dir) throws Exception {
        Path file = config(dir, "baas-123456789012");

        assertThat(refusal("--config-path", file.toString(), "jobs", "list", "--deployment", "other"))
            .contains("other", "baas-123456789012");
    }

    @Test
    void aRefusalRunsNoCommandAndExitsTwo(@TempDir Path dir) throws Exception {
        Path file = config(dir, "baas-123456789012");
        var err = new StringWriter();

        int exit = new CommandLine(new BaasApp())
            .setExecutionStrategy(parseResult -> {
                var app = (BaasApp) parseResult.commandSpec().userObject();
                String refusal = app.deploymentRefusal(parseResult);
                return refusal == null ? new CommandLine.RunLast().execute(parseResult) : 2;
            })
            .setErr(new PrintWriter(err, true))
            .execute("--config-path", file.toString(), "--deployment", "other", "admin", "deployment", "teardown", "--yes");

        assertThat(exit).isEqualTo(2);
    }

    @Test
    void withNothingConfiguredItPointsAtSync(@TempDir Path dir) {
        Path missing = dir.resolve("absent.yaml");

        assertThat(refusal("--config-path", missing.toString(), "--deployment", "x", "jobs", "list"))
            .contains("baas config sync --deployment x");
    }

    @Test
    void syncAndSetupCheckTheNameThemselves(@TempDir Path dir) throws Exception {
        Path file = config(dir, "baas-123456789012");

        assertThat(refusal("--config-path", file.toString(), "--deployment", "other", "config", "sync")).isNull();
        assertThat(refusal("--config-path", file.toString(), "--deployment", "other",
            "admin", "deployment", "setup")).isNull();
    }

    @Test
    void theRemovedPointersAreUnknown() {
        var err = new StringWriter();
        var root = new CommandLine(new BaasApp()).setErr(new PrintWriter(err, true));

        assertThat(root.execute("admin", "deployment", "teardown", "--stack-name", "x")).isNotZero();
        assertThat(new CommandLine(new BaasApp()).setErr(new PrintWriter(err, true))
            .execute("config", "sync", "--name", "x")).isNotZero();
    }
}
