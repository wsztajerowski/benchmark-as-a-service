package pl.wsztajerowski.baas.deployment;

import pl.wsztajerowski.baas.aws.BucketProbe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SetupCommandTest {

    /**
     * {@code --workflow-id} and {@code --workflow-branch} are not reinstated — they configured the
     * GHA dispatch path, which is gone. {@code --oidc-provider-arn} is gone too: the provider's ARN
     * is fixed by the account, so setup derives it. The prefix has its own test below — it is derived from
     * the caller's AWS account and cannot be named on the command line at all.
     */
    @ParameterizedTest
    @ValueSource(strings = {"--workflow-id", "--workflow-branch", "--oidc-provider-arn"})
    void rejectsRemovedGitHubOidcOptions(String removedOption) {
        CommandLine cmd = new CommandLine(new SetupCommand());

        assertThatThrownBy(() -> cmd.parseArgs(removedOption, "some-value"))
            .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
    }

    // ─── GitHub OIDC federation ──────────────────────────────────────────────────

    private static SetupCommand parsed(String... args) {
        var command = new SetupCommand();
        new CommandLine(command).parseArgs(args);
        return command;
    }

    @Test
    void revokingAndSettingAtOnceIsAUsageError() {
        var command = parsed("--revoke-github-oidc", "--github-org", "acme");

        assertThatThrownBy(command::validateFederationOptions)
            .isInstanceOf(CommandLine.ParameterException.class)
            .hasMessageContaining("cannot be combined")
            .hasMessageContaining("Nothing was deployed");
    }

    /**
     * A partial set makes the template's condition evaluate false, so the deploy succeeds and CI
     * silently has no access — the exact failure shape this change exists to remove, arriving
     * from the other direction.
     */
    @Test
    void namingSomeFederationOptionsButNotAllIsAUsageError() {
        assertThatThrownBy(parsed("--github-org", "acme")::validateFederationOptions)
            .isInstanceOf(CommandLine.ParameterException.class)
            .hasMessageContaining("--github-repo");
    }

    @Test
    void aPlainSetupPassesValidation() {
        assertThatCode(parsed("--region", "eu-central-1")::validateFederationOptions)
            .doesNotThrowAnyException();
        assertThatCode(parsed("--revoke-github-oidc")::validateFederationOptions)
            .doesNotThrowAnyException();
    }

    /**
     * The deployed stack's parameters are the single source of truth. A second copy in
     * ~/.baas/config.yaml would drift, and would make "whose laptop ran setup last" part of
     * whether continuous integration keeps working.
     */
    @Test
    void theConfigFileKeepsNoCopyOfTheFederationValues() {
        var configFields = java.util.stream.Stream.of(
                pl.wsztajerowski.baas.session.BaasConfig.class,
                pl.wsztajerowski.baas.session.BaasConfig.AwsConfig.class)
            .flatMap(type -> java.util.Arrays.stream(type.getDeclaredFields()))
            .map(java.lang.reflect.Field::getName)
            .map(name -> name.toLowerCase(java.util.Locale.ROOT))
            .toList();

        assertThat(configFields)
            .noneMatch(name -> name.contains("github"))
            .noneMatch(name -> name.contains("oidc"));
    }

    /**
     * A deployment is named only by the global {@code --deployment}. Setup has no option of its own
     * for it, so no second spelling can disagree with the one every other command reads.
     */
    @ParameterizedTest
    @ValueSource(strings = {"--mode", "--prefix", "--name"})
    void setupHasNoNamingOptionOfItsOwn(String rejected) {
        CommandLine cmd = new CommandLine(new SetupCommand());

        assertThatThrownBy(() -> cmd.parseArgs(rejected, "dev"))
            .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
    }

    // ─── Existing-VPC support is removed ─────────────────────────────────────────

    /** Every deployment uses the VPC its own stack creates (setup-prerequisites). */
    @ParameterizedTest
    @ValueSource(strings = {"--use-existing-vpc", "--vpc-id", "--subnet-id", "--sg-id"})
    void theExistingVpcOptionsAreGone(String removed) {
        CommandLine cmd = new CommandLine(new SetupCommand());

        assertThatThrownBy(() -> cmd.parseArgs(removed, "x"))
            .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
    }

    @Test
    void rejectsTheRemovedMongoUriOption() {
        CommandLine cmd = new CommandLine(new SetupCommand());

        assertThatThrownBy(() -> cmd.parseArgs("--mongo-uri", "mongodb+srv://user:pass@host/db"))
            .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
    }

    /** Every prompt has an option; these two are setup's own, and setup has no --yes. */
    @Test
    void theWalkthroughsOwnOptionsParseAndThereIsNoYes() {
        assertThatCode(() -> new CommandLine(new SetupCommand()).parseArgs("--non-interactive", "--build-image"))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> new CommandLine(new SetupCommand()).parseArgs("--yes"))
            .isInstanceOf(CommandLine.UnmatchedArgumentException.class);
    }

    // ─── --non-interactive on a terminal, through the real command tree ─────────────

    /** Allows only the calls up to a denied permission check; anything later fails the test. */
    private static final class DeniedAws implements pl.wsztajerowski.baas.deployment.setup.SetupAws {
        @Override public Caller callerIdentity() { return new Caller("arn:aws:iam::123456789012:user/alice", "123456789012"); }
        @Override public BucketProbe bucket(String name) { return new BucketProbe.Absent(); }
        @Override public Preflight preflight(String callerArn, String policy) {
            return new Preflight.Denied(java.util.List.of("cloudformation:CreateStack"));
        }
        @Override public boolean tableExists(String name) { throw new AssertionError("not reached"); }
        @Override public java.util.Optional<software.amazon.awssdk.services.cloudformation.model.StackStatus> stackStatus(String s) { throw new AssertionError("not reached"); }
        @Override public java.util.Optional<software.amazon.awssdk.services.cloudformation.model.StackStatus> awaitSettled(String s) { throw new AssertionError("not reached"); }
        @Override public void deleteStack(String s) { throw new AssertionError("not reached"); }
        @Override public java.util.Map<String, String> stackParameters(String s) { throw new AssertionError("not reached"); }
        @Override public java.util.Map<String, String> stackOutputs(String s) { throw new AssertionError("not reached"); }
        @Override public java.util.Map<String, String> imageParameters(java.util.Map<String, String> d) { throw new AssertionError("not reached"); }
        @Override public void submitCreate(String s, java.util.Map<String, String> p) { throw new AssertionError("not reached"); }
        @Override public void awaitCreate(String s) { throw new AssertionError("not reached"); }
        @Override public boolean update(String s, java.util.Map<String, String> p) { throw new AssertionError("not reached"); }
        @Override public void assumeRole(String r) { throw new AssertionError("not reached"); }
        @Override public OidcProvider oidcProvider(String a) { throw new AssertionError("not reached"); }
        @Override public void createOidcProvider() { throw new AssertionError("not reached"); }
        @Override public Image image(String d) { throw new AssertionError("not reached"); }
        @Override public java.util.Optional<RunningBuild> runningImageBuild(String d) { throw new AssertionError("not reached"); }
        @Override public String buildImage(String d) { throw new AssertionError("not reached"); }
    }

    /** Runs setup through {@code BaasApp} on a console that says terminal; returns lines read and the exit. */
    private static int[] onATerminal(java.nio.file.Path root, java.io.StringWriter out, String... args) {
        int[] reads = {0};
        CommandLine.IFactory defaults = CommandLine.defaultFactory();
        CommandLine.IFactory factory = new CommandLine.IFactory() {
            @Override
            @SuppressWarnings("unchecked")
            public <K> K create(Class<K> cls) throws Exception {
                if (cls == SetupCommand.class) {
                    var command = new SetupCommand();
                    command.awsFactory = (region, profile) -> new DeniedAws();
                    command.consoleFor = writer -> pl.wsztajerowski.baas.console.Console.withFlags(writer, true, false);
                    command.answerReader = () -> { reads[0]++; return null; };
                    return (K) command;
                }
                return defaults.create(cls);
            }
        };
        int exit = new CommandLine(new pl.wsztajerowski.baas.BaasApp(root), factory)
            .setOut(new java.io.PrintWriter(out, true))
            .setErr(new java.io.PrintWriter(new java.io.StringWriter(), true))
            .execute(args);
        return new int[]{reads[0], exit};
    }

    /** Spec: "The option forces unattended on a terminal". */
    @Test
    void theOptionForcesUnattendedOnATerminal(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) {
        var out = new java.io.StringWriter();

        int[] result = onATerminal(root, out, "admin", "deployment", "setup", "--non-interactive", "--region", "us-east-1");

        assertThat(result[0]).as("lines read: nothing is asked").isZero();
        assertThat(result[1]).isEqualTo(1);
        assertThat(out.toString().strip()).as("unattended, the policy is the payload").startsWith("{").endsWith("}");
    }

    /** The control: the same terminal without the option asks. */
    @Test
    void withoutTheOptionATerminalIsAsked(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) {
        int[] result = onATerminal(root, new java.io.StringWriter(), "admin", "deployment", "setup", "--region", "us-east-1");

        assertThat(result[0]).as("the profile prompt reads a line").isPositive();
    }
}
