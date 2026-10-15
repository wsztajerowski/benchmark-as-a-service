package pl.wsztajerowski.baas.config;

import pl.wsztajerowski.baas.cli.Invocation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import pl.wsztajerowski.baas.BaasApp;
import pl.wsztajerowski.baas.TestDeployments;
import pl.wsztajerowski.baas.session.BaasConfig;
import pl.wsztajerowski.baas.session.ConfigService;
import pl.wsztajerowski.baas.aws.AwsClientFactory;
import pl.wsztajerowski.baas.session.OperatorSession;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.sts.model.StsException;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigSyncSubcommandTest {

    @TempDir
    Path dir;

    /**
     * Required with nothing configured, although the prefix is derivable. A bare sync on a machine
     * with no local state would adopt whatever deployment the active credentials imply — in CI, a
     * wrong role or a leftover AWS_PROFILE binding the machine to another account's deployment,
     * discovered only after something has been provisioned.
     */
    @Test
    void withNothingConfiguredTheNameIsRequired() {
        int exit = sync(deployment("eu-central-1", Map.of("ResultsTableName", "t")));

        assertThat(exit).isEqualTo(2);
        assertThat(dir.resolve("deployments")).doesNotExist();
    }

    @Test
    void theOnlyDeploymentIsReSyncedWithoutAName() throws Exception {
        TestDeployments.writeRaw(dir, "baas-123456789012-dev",
            "prefix: \"baas-123456789012-dev\"\naws:\n  region: \"eu-west-1\"\n");

        int exit = sync(deployment("us-east-1", Map.of("ResultsTableName", "t")));

        assertThat(exit).isZero();
        assertThat(load("baas-123456789012-dev").getAws().getRegion()).isEqualTo("us-east-1");
        assertThat(service().deployments()).containsExactly("baas-123456789012-dev");
    }

    @Test
    void withTwoConfiguredABareSyncIsAmbiguous() throws Exception {
        TestDeployments.write(dir, TestDeployments.DEFAULT);
        TestDeployments.write(dir, "wiktor-dev");

        assertThat(sync(deployment("eu-central-1", Map.of("ResultsTableName", "t")))).isEqualTo(2);
    }

    @Test
    void theRemovedNameOptionsAreGone() {
        var names = new CommandLine(new ConfigSyncSubcommand()).getCommandSpec().options().stream()
            .flatMap(option -> java.util.Arrays.stream(option.names()))
            .toList();

        assertThat(names).doesNotContain("--name", "--core-stack-name");
    }

    @Test
    void theNameIsTheGlobalDeploymentOption() {
        var parsed = new CommandLine(new BaasApp(dir))
            .parseArgs("config", "sync", "--deployment", "baas-123456789012-dev");

        assertThat(parsed.subcommand().subcommand().commandSpec().userObject())
            .isInstanceOf(ConfigSyncSubcommand.class);
        assertThat(Invocation.deployment(parsed.subcommand().subcommand().commandSpec()))
            .contains("baas-123456789012-dev");
    }

    /**
     * A second deployment is adopted beside the first, in its own file. The stack lookup is stood
     * in for: the test has no stack, and the file handling is what is under test.
     */
    @Test
    void adoptingASecondDeploymentLeavesTheFirstAlone() throws Exception {
        Path main = TestDeployments.write(dir, TestDeployments.DEFAULT);
        String mainBefore = Files.readString(main);

        int exit = sync(deployment("eu-central-1", Map.of("ResultsTableName", "t")),
            "--deployment", "baas-123456789012-dev");

        assertThat(exit).isZero();
        assertThat(load("baas-123456789012-dev").getPrefix()).isEqualTo("baas-123456789012-dev");
        assertThat(Files.readString(main)).isEqualTo(mainBefore);
        assertThat(service().deployments()).containsExactly("baas-123456789012", "baas-123456789012-dev");
    }

    @Test
    void aMissingStackWritesNothing() {
        int exit = sync(deployment("eu-central-1", Map.of()), "--deployment", "baas-nope");

        assertThat(exit).as("a bucket with no stack is not a deployment").isNotZero();
        assertThat(service().fileOf("baas-nope")).doesNotExist();
    }

    @Test
    void noBucketMeansNoDeploymentAndWritesNothing() {
        int exit = sync(deployment(null, Map.of("ResultsTableName", "t")), "--deployment", "baas-nope");

        assertThat(exit).isNotZero();
        assertThat(service().fileOf("baas-nope")).doesNotExist();
    }

    /**
     * The region is found from the bucket and stored, whatever region the machine started in: the
     * deployment's region is a fact about it, so a machine or a CI job aimed elsewhere follows it.
     */
    @Test
    void syncStoresTheRegionTheDeploymentLivesIn() throws Exception {
        TestDeployments.writeRaw(dir, "baas-123456789012-dev",
            "prefix: \"baas-123456789012-dev\"\naws:\n  region: \"eu-west-1\"\n");

        int exit = sync(deployment("us-east-1", Map.of("ResultsTableName", "t")),
            "--deployment", "baas-123456789012-dev");

        assertThat(exit).isZero();
        assertThat(load("baas-123456789012-dev").getAws().getRegion()).isEqualTo("us-east-1");
    }

    /** Spec: sync stores the operator profile it was given, once that profile has proved it can assume the role. */
    @Test
    void theOperatorProfileIsStoredOnceItWorks() {
        int exit = run(deployment("us-east-1", Map.of("ResultsTableName", "t")),
            "--deployment", "baas-123456789012", "config", "sync", "--operator-aws-profile", "my-sso");

        assertThat(exit).isZero();
        assertThat(load("baas-123456789012").getAws().getOperatorProfile()).isEqualTo("my-sso");
        assertThat(load("baas-123456789012").getAws().getRegion()).isEqualTo("us-east-1");
    }

    /** Spec: sync refuses credentials that cannot operate the deployment, and writes no file. */
    @Test
    void credentialsThatCannotAssumeTheRoleWriteNothing() {
        int exit = run(deployment("us-east-1", Map.of("ResultsTableName", "t"), false),
            "--deployment", "baas-123456789012", "config", "sync", "--operator-aws-profile", "other");

        assertThat(exit).isNotZero();
        assertThat(service().fileOf("baas-123456789012")).doesNotExist();
    }

    private int sync(CommandLine.IFactory factory, String... options) {
        String[] args = new String[options.length + 2];
        System.arraycopy(options, 0, args, 0, options.length);
        args[options.length] = "config";
        args[options.length + 1] = "sync";
        return run(factory, args);
    }

    private int run(CommandLine.IFactory factory, String... args) {
        return new CommandLine(new BaasApp(dir), factory)
            .setOut(new PrintWriter(new StringWriter()))
            .setErr(new PrintWriter(new StringWriter()))
            .execute(args);
    }

    private ConfigService service() {
        return new ConfigService(dir, Optional.empty());
    }

    private BaasConfig load(String deployment) {
        return new ConfigService(dir, Optional.of(deployment)).load();
    }

    /** A bucket in {@code bucketRegion} (none when null) and a stack there with {@code outputs}. */
    private static CommandLine.IFactory deployment(String bucketRegion, Map<String, String> outputs) {
        return deployment(bucketRegion, outputs, true);
    }

    /**
     * As above, with credentials that may ({@code canAssume}) or may not assume the operator role.
     * The caller is an IAM user, so every sync takes path C.
     */
    private static CommandLine.IFactory deployment(String bucketRegion, Map<String, String> outputs,
                                                   boolean canAssume) {
        CommandLine.IFactory defaults = CommandLine.defaultFactory();
        return new CommandLine.IFactory() {
            @Override
            @SuppressWarnings("unchecked")
            public <K> K create(Class<K> cls) throws Exception {
                if (cls == ConfigSyncSubcommand.class) {
                    return (K) new ConfigSyncSubcommand() {
                        @Override
                        OperatorSession.Aws aws() {
                            return new OperatorSession.Aws() {
                                public OperatorSession.Caller callerIdentity(AwsCredentialsProvider source, String region) {
                                    return new OperatorSession.Caller("arn:aws:iam::123456789012:user/someone", "123456789012");
                                }

                                public AwsCredentialsProvider assume(AwsCredentialsProvider source, String region, String roleArn) {
                                    if (!canAssume) {
                                        throw StsException.builder().message("not authorized to perform sts:AssumeRole").build();
                                    }
                                    return StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIA", "secret"));
                                }

                                public Optional<String> bucketRegion(AwsCredentialsProvider credentials, String bucket, String from) {
                                    return Optional.ofNullable(bucketRegion);
                                }
                            };
                        }

                        @Override
                        Map<String, String> stackOutputs(AwsClientFactory factory, String stack) {
                            return factory.region().equals(bucketRegion) ? outputs : Map.of();
                        }
                    };
                }
                return defaults.create(cls);
            }
        };
    }
}
