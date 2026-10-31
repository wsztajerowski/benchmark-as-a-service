package pl.wsztajerowski.baas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

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
        if (!Files.exists(dir.resolve("deployments"))) {
            TestDeployments.write(dir, TestDeployments.DEFAULT);
        }
        var out = new StringWriter();
        var err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int code = BaasApp.commandLine(new BaasApp(dir)).setOut(new PrintWriter(out)).execute(args);
            return new Captured(out.toString(), err.toString(StandardCharsets.UTF_8), code);
        } finally {
            System.setErr(originalErr);
        }
    }

    /**
     * An invalid name is refused before the caller-identity lookup, the policy step and every other
     * AWS call. A message naming the rule, not the account or AWS, proves the order.
     */
    @Test
    void anInvalidDeploymentNameIsRefusedBeforeAnyAwsCall() throws Exception {
        var captured = baas("--deployment", "aws-dev", "admin", "deployment", "setup");

        assertThat(captured.exitCode()).isEqualTo(2);
        assertThat(captured.err())
            .contains("Deployment name 'aws-dev' must not start with \"aws\" (reserved by SSM)")
            .doesNotContain("not this account's deployment");
        assertThat(captured.out()).as("no policy is printed").isEmpty();
    }

    /** U25: refused before the JAR is even looked at, so long before anything is uploaded. */
    @Test
    void aReservedTagIsRefusedBeforeTheJarOrAnyUpload() throws Exception {
        var captured = baas("run", "--runner-jar", "/nonexistent-runner.jar",
            "--benchmark-jar", "/nonexistent.jar", "--project", "p", "--tag", "jdkVersion=21", "jmh");

        assertThat(captured.exitCode()).isNotZero();
        assertThat(captured.err()).contains("--tag jdkVersion").doesNotContain("Benchmark JAR not found");
    }

    @Test
    void aProjectTagIsRefusedBeforeTheJarOrAnyUpload() throws Exception {
        var captured = baas("run", "--runner-jar", "/nonexistent-runner.jar",
            "--benchmark-jar", "/nonexistent.jar", "--project", "p", "--tag", "project=q", "jmh");

        assertThat(captured.exitCode()).isNotZero();
        assertThat(captured.err()).contains("--tag project").doesNotContain("Benchmark JAR not found");
    }

    /** JCStress's own -m passes: the benchmark's arguments reach its JAR verbatim. */
    @Test
    void jcstressesOwnModeFlagPasses() throws Exception {
        var captured = baas("run", "--runner-jar", "/nonexistent-runner.jar",
            "--benchmark-jar", "/nonexistent.jar", "--project", "p", "jcstress", "--", "-m", "sanity");

        assertThat(captured.err()).doesNotContain("managed by BaaS").contains("Benchmark JAR not found");
    }

    /** A flag BaaS manages is refused as typed, before the JAR, the project or AWS are looked at. */
    @Test
    void aManagedFlagIsRefusedAsTyped() throws Exception {
        var captured = baas("run", "--runner-jar", "/nonexistent-runner.jar",
            "--benchmark-jar", "/nonexistent.jar", "--project", "p", "jmh", "--", "MyBenchmark", "-rf", "csv");

        assertThat(captured.exitCode()).isEqualTo(2);
        assertThat(captured.err()).contains("-rf is managed by BaaS").doesNotContain("Benchmark JAR not found");
    }

    /** run's help lists the two types and says how profiling is asked for now. */
    @Test
    void runsHelpNamesTheTypesAndProfiling() throws Exception {
        var captured = baas("run", "--help");

        assertThat(captured.out()).contains("jmh, jcstress", "-prof gc", "jcstress -- -m sanity")
            .doesNotContain("jmh-with");
    }

    /** A retired type is refused naming the two that remain. */
    @Test
    void aRetiredTypeIsRefused() throws Exception {
        var captured = baas("run", "--runner-jar", "/nonexistent-runner.jar",
            "--benchmark-jar", "/nonexistent.jar", "--project", "p", "jmh-with-async", "--", "MyBenchmark");

        assertThat(captured.exitCode()).isEqualTo(2);
        assertThat(captured.err()).contains("[jmh, jcstress]");
    }

    /** The removed MongoDB option is no longer the CLI's to refuse: nothing types it. */
    @Test
    void theRemovedMongoOptionIsNotRefusedByTheCli() throws Exception {
        var captured = baas("run", "--runner-jar", "/nonexistent-runner.jar",
            "--benchmark-jar", "/nonexistent.jar", "--project", "p", "jmh", "--", "--mongo-connection-string", "x");

        assertThat(captured.err()).doesNotContain("managed by BaaS").contains("Benchmark JAR not found");
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
        assertThat(Files.readString(dir.resolve("deployments").resolve(TestDeployments.DEFAULT + ".yaml")))
            .doesNotContain("benchmarkTimeoutSeconds");
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
        assertThat(captured.err()).contains("created")
            .as("a refusal has no stack trace worth asking for").doesNotContain("run with -v");
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
        String help = BaasApp.commandLine(new BaasApp()).getSubcommands().get("jobs")
            .getSubcommands().get("diff").getUsageMessage();

        assertThat(help).doesNotContain("has no index entry");
    }
}
