package pl.wsztajerowski.baas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --deployment} is the one pointer to a concrete deployment. Absent, the only configured
 * deployment is used; with two or more a command must name one. Every refusal below happens while
 * reading the machine's own files, before any AWS call — the test machine's home has no credentials,
 * so a message about deployments, not about AWS, proves the order.
 */
class DeploymentOptionTest {

    @TempDir
    Path dir;

    record Captured(int exitCode, String err) {}

    private Captured baas(String... args) {
        // Both streams: a failure goes through BaasApp.main's handler, which logs to System.err, and a
        // usage error picocli handles itself reaches the command line's error writer.
        var err = new ByteArrayOutputStream();
        var picocliErr = new StringWriter();
        PrintStream original = System.err;
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int code = new CommandLine(new BaasApp(dir))
                .setOut(new PrintWriter(new StringWriter()))
                .setErr(new PrintWriter(picocliErr, true))
                .setExecutionExceptionHandler(BaasApp::reportFailure)
                .execute(args);
            return new Captured(code, err.toString(StandardCharsets.UTF_8) + picocliErr);
        } finally {
            System.setErr(original);
        }
    }

    @Test
    void twoDeploymentsRequireTheFlagAndListBothWithTheHint() throws Exception {
        TestDeployments.write(dir, TestDeployments.DEFAULT);
        TestDeployments.write(dir, "wiktor-dev");

        var run = baas("jobs", "list");

        assertThat(run.exitCode()).isNotZero();
        assertThat(run.err())
            .contains("2 deployments are configured (baas-123456789012, wiktor-dev)")
            .contains("→ choose one: baas config list")
            .doesNotContain("full stack trace");
    }

    /** W1: a selection refusal says what to type; only a real failure points at {@code -v}. */
    @Test
    void onlyARealFailurePointsAtTheStackTrace() {
        var refusal = baas("jobs", "list");
        assertThat(refusal.err()).contains("No deployment is configured").doesNotContain("full stack trace");

        var err = new ByteArrayOutputStream();
        PrintStream original = System.err;
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            BaasApp.reportFailure(new RuntimeException("Stack wiktor-dev is in UPDATE_ROLLBACK_FAILED"),
                new CommandLine(new BaasApp(dir)), null);
        } finally {
            System.setErr(original);
        }
        assertThat(err.toString(StandardCharsets.UTF_8))
            .contains("UPDATE_ROLLBACK_FAILED", "(run with -v for the full stack trace)");
    }

    @Test
    void teardownIsNeverAmbiguous() throws Exception {
        TestDeployments.write(dir, TestDeployments.DEFAULT);
        TestDeployments.write(dir, "wiktor-dev");

        var run = baas("admin", "deployment", "teardown", "--yes");

        assertThat(run.exitCode()).isNotZero();
        assertThat(run.err()).contains("2 deployments are configured");
        assertThat(dir.resolve("deployments").resolve("wiktor-dev.yaml")).exists();
        assertThat(dir.resolve("deployments").resolve("baas-123456789012.yaml")).exists();
    }

    @Test
    void aDifferentDeploymentIsRefusedNamingTheConfiguredOnes() throws Exception {
        TestDeployments.write(dir, TestDeployments.DEFAULT);

        var run = baas("--deployment", "other", "results", "query");

        assertThat(run.exitCode()).isNotZero();
        assertThat(run.err()).contains("No deployment 'other'", "configured: baas-123456789012");
    }

    @Test
    void theOptionParsesOnEitherSideOfTheSubcommand() throws Exception {
        TestDeployments.write(dir, TestDeployments.DEFAULT);

        assertThat(baas("jobs", "list", "--deployment", "other").err()).contains("No deployment 'other'");
        assertThat(baas("--deployment", "other", "jobs", "list").err()).contains("No deployment 'other'");
    }

    @Test
    void withNothingConfiguredACommandSaysSo() {
        var run = baas("jobs", "list");

        assertThat(run.exitCode()).isNotZero();
        assertThat(run.err()).contains("No deployment is configured", "baas admin deployment setup");
    }

    /**
     * {@code BAAS_DEPLOYMENT} is not read. The JVM cannot set an environment variable, so this holds
     * the code to the rule rather than proving the variable is ignored at run time.
     */
    @Test
    void noEnvironmentVariableIsConsulted() throws Exception {
        String sources = java.nio.file.Files.readString(Path.of(
            "src/main/java/pl/wsztajerowski/baas/config/ConfigService.java"))
            + java.nio.file.Files.readString(Path.of("src/main/java/pl/wsztajerowski/baas/BaasApp.java"));

        assertThat(sources).doesNotContain("getenv").doesNotContain("BAAS_DEPLOYMENT");
    }

    @Test
    void theRemovedPointersAreUnknown() throws Exception {
        TestDeployments.write(dir, TestDeployments.DEFAULT);

        assertThat(baas("admin", "deployment", "teardown", "--stack-name", "x").exitCode()).isEqualTo(2);
        assertThat(baas("config", "sync", "--name", "x").exitCode()).isEqualTo(2);
        assertThat(baas("--config-path", "f.yaml", "results", "query").exitCode()).isEqualTo(2);
    }
}
