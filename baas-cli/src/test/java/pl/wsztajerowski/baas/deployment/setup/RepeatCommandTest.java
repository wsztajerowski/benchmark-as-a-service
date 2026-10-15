package pl.wsztajerowski.baas.deployment.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import pl.wsztajerowski.baas.BaasApp;
import pl.wsztajerowski.baas.TestDeployments;
import software.amazon.awssdk.services.cloudformation.model.StackStatus;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The repeat command is the answers rendered as options, so parsing it back through the real
 * command tree must yield the same answers — and a prompt without an option fails here.
 */
class RepeatCommandTest {

    @TempDir
    Path root;

    private CommandLine.ParseResult parse(List<String> tokens) {
        assertThat(tokens.getFirst()).isEqualTo("baas");
        return new CommandLine(new BaasApp(root)).parseArgs(tokens.subList(1, tokens.size()).toArray(String[]::new));
    }

    private static CommandLine.ParseResult setupOf(CommandLine.ParseResult result) {
        return result.subcommand().subcommand().subcommand();
    }

    @Test
    void aCreateRepeatsWithItsRegionFederationAndImage() throws Exception {
        var run = new SetupRun(root).interactive("baas-admin", "team-bench", "us-east-1", "y", "acme", "one, two", "", "");

        assertThat(run.run()).isZero();
        var tokens = RepeatCommand.tokens(run.context);
        var parsed = parse(tokens);
        var setup = setupOf(parsed);

        assertThat(parsed.matchedOptionValue("--deployment", "")).isEqualTo("team-bench");
        assertThat(setup.commandSpec().name()).isEqualTo("setup");
        assertThat(setup.hasMatchedOption("--non-interactive")).isTrue();
        assertThat(setup.matchedOptionValue("--region", "")).isEqualTo("us-east-1");
        assertThat(setup.matchedOptionValue("--deployer-aws-profile", "")).isEqualTo("baas-admin");
        assertThat(setup.matchedOptionValue("--github-org", "")).isEqualTo("acme");
        assertThat(setup.<List<String>>matchedOptionValue("--github-repo", List.of())).containsExactly("one", "two");
        assertThat(setup.hasMatchedOption("--build-image")).isTrue();
        assertThat(RepeatCommand.render(run.context))
            .isEqualTo("baas --deployment team-bench admin deployment setup --non-interactive --region us-east-1 "
                + "--deployer-aws-profile baas-admin --github-org acme --github-repo one,two --build-image");
    }

    /** The region is chosen once: an update's repeat leaves it to the file. */
    @Test
    void anUpdateRepeatsWithoutTheRegion() throws Exception {
        TestDeployments.writeRaw(root, "dev", "aws:\n  region: \"us-east-1\"\n  deployerProfile: \"baas-admin\"\n");
        var run = new SetupRun(root).interactive("", "y", "org", "a,b", "");
        run.aws.stacks.put("dev", StackStatus.CREATE_COMPLETE);
        run.aws.stackParameters.put("dev", Map.of("GitHubOrg", "org", "GitHubRepo", "repo:org/a:*"));
        run.aws.provider = new SetupAws.OidcProvider.Present(List.of(SetupAws.STS_AUDIENCE));
        run.aws.image = new SetupAws.Image.Current("1.3.0");

        assertThat(run.run()).isZero();
        var setup = setupOf(parse(RepeatCommand.tokens(run.context)));

        assertThat(setup.hasMatchedOption("--region")).isFalse();
        assertThat(setup.<List<String>>matchedOptionValue("--github-repo", List.of())).containsExactly("a", "b");
    }

    @Test
    void aRevocationRepeatsAsTheRevokeOption() throws Exception {
        TestDeployments.writeRaw(root, "dev", "aws:\n  region: \"us-east-1\"\n");
        var run = new SetupRun(root).interactive("", "y", "-", "");
        run.aws.stacks.put("dev", StackStatus.CREATE_COMPLETE);
        run.aws.stackParameters.put("dev", Map.of("GitHubOrg", "org", "GitHubRepo", "repo:org/a:*"));
        run.aws.image = new SetupAws.Image.Current("1.3.0");

        assertThat(run.run()).isZero();
        assertThat(setupOf(parse(RepeatCommand.tokens(run.context))).hasMatchedOption("--revoke-github-oidc")).isTrue();
    }

    @Test
    void nothingChangedMeansNothingToRepeat() throws Exception {
        TestDeployments.writeRaw(root, "dev", "aws:\n  region: \"us-east-1\"\n  deployerProfile: \"baas-admin\"\n");
        var run = new SetupRun(root).interactive("", "");
        run.aws.stacks.put("dev", StackStatus.CREATE_COMPLETE);
        run.aws.image = new SetupAws.Image.Current("1.3.0");
        run.aws.updateChanges = false;

        assertThat(run.run()).isZero();
        assertThat(run.context.changedAnything()).isFalse();
    }
}
