package pl.wsztajerowski.baas.deployment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pl.wsztajerowski.baas.aws.CloudFormationService;
import pl.wsztajerowski.baas.console.Console;
import pl.wsztajerowski.baas.deployment.setup.GitHubTrust;
import pl.wsztajerowski.baas.image.RunnerImage;
import pl.wsztajerowski.baas.session.CoreStack;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code admin deployment show}'s two renderings, from a report built without AWS (G14). */
class DeploymentShowSubcommandTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final CloudFormationService.StackView STACK = new CloudFormationService.StackView(
        "UPDATE_COMPLETE", "2026-11-01T10:00:00Z",
        Map.of(CoreStack.PARAM_BAAS_CLI_VERSION, "9.0.0"),
        new TreeMap<>(Map.of(CoreStack.OUTPUT_OPERATOR_ROLE_ARN, "arn:aws:iam::1:role/dev-role-operator",
            CoreStack.OUTPUT_SUBNET_ID, "subnet-1")));

    private static final DeploymentShowSubcommand.Report FULL = new DeploymentShowSubcommand.Report(
        "dev", "eu-central-1", STACK, new GitHubTrust("acme", List.of("bench", "lib")),
        new RunnerImage("ami-1", "1.5.0", "ami-parent", "2026-11-01T00:00:00Z"), "/dev/runner/ami-id");

    private static final DeploymentShowSubcommand.Report BARE = new DeploymentShowSubcommand.Report(
        "dev", "eu-central-1", new CloudFormationService.StackView("CREATE_COMPLETE", "2026-10-01T00:00:00Z",
            Map.of(), Map.of()), null, null, "/dev/runner/ami-id");

    private static String text(DeploymentShowSubcommand.Report report) {
        var out = new StringWriter();
        DeploymentShowSubcommand.text(Console.plain(new PrintWriter(out, true)), report);
        return out.toString();
    }

    @Test
    void theTextNamesTheStackTrustImageAndOutputs() {
        assertThat(text(FULL)).contains(
            "Deployment  dev (eu-central-1)",
            "Stack       UPDATE_COMPLETE, last updated 2026-11-01T10:00:00Z",
            "Set up by   baas 9.0.0",
            "GitHub      acme/bench, acme/lib may run benchmarks",
            "Image       1.5.0 (ami-1)",
            "  OperatorRoleArn  arn:aws:iam::1:role/dev-role-operator",
            "  SubnetId         subnet-1");
    }

    @Test
    void theTextSaysWhatIsAbsent() {
        assertThat(text(BARE)).contains(
            "Set up by   (not recorded)",
            "GitHub      no repository may run benchmarks",
            "Image       none published (/dev/runner/ami-id)");
    }

    @Test
    void theJsonIsOneObjectWithTheSameFacts() throws Exception {
        JsonNode json = JSON.readTree(DeploymentShowSubcommand.json(FULL));

        assertThat(json.get("deployment").asText()).isEqualTo("dev");
        assertThat(json.get("region").asText()).isEqualTo("eu-central-1");
        assertThat(json.at("/stack/status").asText()).isEqualTo("UPDATE_COMPLETE");
        assertThat(json.at("/stack/lastUpdated").asText()).isEqualTo("2026-11-01T10:00:00Z");
        assertThat(json.at("/stack/outputs/OperatorRoleArn").asText()).isEqualTo("arn:aws:iam::1:role/dev-role-operator");
        assertThat(json.get("cliVersion").asText()).isEqualTo("9.0.0");
        assertThat(json.at("/github/org").asText()).isEqualTo("acme");
        assertThat(json.at("/github/repos")).hasSize(2);
        assertThat(json.at("/image/version").asText()).isEqualTo("1.5.0");
        assertThat(json.at("/image/amiId").asText()).isEqualTo("ami-1");
    }

    @Test
    void absentTrustImageAndVersionAreNullInJson() throws Exception {
        JsonNode json = JSON.readTree(DeploymentShowSubcommand.json(BARE));

        assertThat(json.get("github").isNull()).isTrue();
        assertThat(json.get("image").isNull()).isTrue();
        assertThat(json.get("cliVersion").isNull()).isTrue();
        assertThat(json.at("/stack/outputs").isEmpty()).isTrue();
    }

    @Test
    void stackValuesAreEscapedInJson() throws Exception {
        var report = new DeploymentShowSubcommand.Report("dev", "eu-central-1",
            new CloudFormationService.StackView("UPDATE_COMPLETE", "t", Map.of(), Map.of("Note", "a \"quoted\"\nvalue")),
            null, null, "/p");

        assertThat(JSON.readTree(DeploymentShowSubcommand.json(report)).at("/stack/outputs/Note").asText())
            .isEqualTo("a \"quoted\"\nvalue");
    }

    @Test
    void theTrustIsReadFromTheStacksParameters() {
        assertThat(GitHubTrust.deployed(Map.of(CoreStack.PARAM_GITHUB_ORG, "acme",
            CoreStack.PARAM_GITHUB_REPO, "repo:acme/bench:*,repo:acme/lib:*")))
            .contains(new GitHubTrust("acme", List.of("bench", "lib")));
        assertThat(GitHubTrust.deployed(Map.of(CoreStack.PARAM_GITHUB_ORG, "", CoreStack.PARAM_GITHUB_REPO, ""))).isEmpty();
    }
}
