package pl.wsztajerowski.baas.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import pl.wsztajerowski.baas.BaasApp;
import pl.wsztajerowski.baas.TestDeployments;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Local and credential-free: the test home has no AWS configuration at all, so a listing that
 * reached AWS would fail here rather than print.
 */
class ConfigListSubcommandTest {

    @TempDir
    Path dir;

    record Run(int exit, String out) {}

    private Run list(String... options) {
        var out = new StringWriter();
        String[] args = new String[options.length + 2];
        args[0] = "config";
        args[1] = "list";
        System.arraycopy(options, 0, args, 2, options.length);
        int exit = new CommandLine(new BaasApp(dir))
            .setOut(new PrintWriter(out, true))
            .setErr(new PrintWriter(new StringWriter()))
            .execute(args);
        return new Run(exit, out.toString());
    }

    @Test
    void twoDeploymentsAreListedWithTheirRegionsAndProfiles() throws Exception {
        TestDeployments.writeRaw(dir, "baas-123456789012", """
            prefix: "baas-123456789012"
            aws:
              deployerProfile: "baas-admin"
              operatorProfile: "baas-operator"
              region: "eu-central-1"
            """);
        TestDeployments.writeRaw(dir, "wiktor-dev", """
            prefix: "wiktor-dev"
            aws:
              region: "us-east-1"
            """);

        var run = list();

        assertThat(run.exit()).isZero();
        assertThat(run.out())
            .contains("DEPLOYMENT", "REGION", "OPERATOR PROFILE", "DEPLOYER PROFILE")
            .contains("baas-123456789012", "eu-central-1", "baas-operator", "baas-admin")
            .contains("wiktor-dev", "us-east-1");
    }

    @Test
    void itListsWhenOtherCommandsWouldRefuseToGuess() throws Exception {
        TestDeployments.write(dir, "baas-123456789012");
        TestDeployments.write(dir, "wiktor-dev");

        assertThat(list().exit()).as("no deployment needs selecting").isZero();
    }

    @Test
    void jsonIsOneObjectPerDeploymentAndNothingElse() throws Exception {
        TestDeployments.write(dir, "baas-123456789012");
        TestDeployments.write(dir, "wiktor-dev");

        var run = list("--format", "json");

        JsonNode rows = new ObjectMapper().readTree(run.out());
        assertThat(rows.isArray()).isTrue();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("deployment").asText()).isEqualTo("baas-123456789012");
        assertThat(rows.get(1).get("region").asText()).isEqualTo("eu-central-1");
        assertThat(rows.get(1).get("operatorProfile").isNull()).isTrue();
    }

    @Test
    void withNoneConfiguredItSaysSoAndSucceeds() {
        var run = list();

        assertThat(run.exit()).isZero();
        assertThat(run.out()).contains("No deployment is configured");
        assertThat(list("--format", "json").out().strip()).isEqualTo("[\n]");
    }

    @Test
    void aFlatConfigurationIsMigratedAndListed() throws Exception {
        Files.writeString(dir.resolve("config.yaml"), "prefix: \"baas-123456789012\"\n");

        assertThat(list().out()).contains("baas-123456789012");
        assertThat(dir.resolve("config.yaml")).doesNotExist();
    }

    @Test
    void listHasNoTopLevelAlias() {
        assertThat(new CommandLine(new BaasApp(dir)).getSubcommands()).doesNotContainKey("list");
    }
}
