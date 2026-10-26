package pl.wsztajerowski.baas.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;
import pl.wsztajerowski.baas.BaasApp;
import pl.wsztajerowski.baas.session.BaasConfig;

import static org.assertj.core.api.Assertions.assertThat;

class ImageCommandsTest {

    @ParameterizedTest
    @ValueSource(strings = {"build", "show"})
    void resolvesUnderAdmin(String subcommand) {
        CommandLine.ParseResult result = BaasApp.commandLine(new BaasApp())
            .parseArgs("admin", "image", subcommand, "--help");

        assertThat(result.subcommand().commandSpec().name()).isEqualTo("admin");
        assertThat(result.subcommand().subcommand().commandSpec().name()).isEqualTo("image");
        assertThat(result.subcommand().subcommand().subcommand().commandSpec().name()).isEqualTo(subcommand);
    }

    /**
     * Building an image is a deployer operation — it needs {@code imagebuilder:*},
     * {@code ssm:PutParameter} on the pointer and a widened {@code iam:PassRole}. Resolving it from
     * {@code aws.operatorProfile} would either fail confusingly or, worse, work because someone
     * had pointed that field at deployer credentials.
     */
    @ParameterizedTest
    @ValueSource(strings = {"build-image", "image", "build"})
    void isNotResolvableAsATopLevelCommand(String subcommand) {
        assertThat(BaasApp.commandLine(new BaasApp()).getSubcommands())
            .as("these sit under admin precisely because they need deployer credentials")
            .doesNotContainKey(subcommand);
    }

    /**
     * Both commands read {@code aws.deployerProfile} directly rather than
     * {@link BaasConfig.AwsConfig#resolveOperatorProfile()}, which is the accessor the day-to-day
     * commands use. The two fields must not be confusable.
     */
    @Test
    void deployerProfileIsTheOneTheseCommandsRead() {
        var aws = new BaasConfig.AwsConfig();
        aws.setDeployerProfile("baas-deployer");
        aws.setOperatorProfile("baas-operator");

        assertThat(aws.getDeployerProfile())
            .as("`baas admin image build` builds its clients from this field")
            .isEqualTo("baas-deployer");
        assertThat(aws.operatorSourceProfile())
            .as("and never from this one, which only ever assumes the operator role")
            .isEqualTo("baas-operator");
    }

    @ParameterizedTest
    @ValueSource(strings = {"build", "show"})
    void verboseIsAcceptedSoTheArgvPreScanApplies(String subcommand) {
        CommandLine.ParseResult result = BaasApp.commandLine(new BaasApp())
            .parseArgs("admin", "image", subcommand, "-v");

        assertThat(result.subcommand().subcommand().subcommand().commandSpec().name()).isEqualTo(subcommand);
    }

    @Test
    void adminListsBothImageCommands() {
        assertThat(BaasApp.commandLine(new BaasApp()).getSubcommands().get("admin").getSubcommands()
            .get("image").getSubcommands())
            .containsKeys("build", "show");
    }

    // ─── an older bundled base (U39) ─────────────────────────────────────────────

    @Test
    void versionsOrderNumericallyNotAsText() {
        assertThat(RunnerImageParameters.compareVersions("1.10.0", "1.9.0")).isPositive();
        assertThat(RunnerImageParameters.compareVersions("1.2.0", "1.3.0")).isNegative();
        assertThat(RunnerImageParameters.compareVersions("2.0.0", "2.0.0")).isZero();
    }

    /** Building it would replace the newer component, and the older one registers cleanly. */
    @Test
    void buildImageRefusesABaseOlderThanTheDeployments() {
        assertThat(RunnerImageBuild.olderBaseRefusal("1.3.0", "1.4.0", "baas-123456789012"))
            .contains("1.3.0", "1.4.0", "baas-123456789012", "Upgrade the CLI", "Nothing was changed")
            .as("the installer copy and its --update are gone since 8.0.0: the newest release's installer is the update")
            .contains("/releases/latest/download/install.sh | sh")
            .doesNotContain("--update");
    }

    @Test
    void buildImageAllowsTheSameOrANewerBaseAndAFirstBuild() {
        assertThat(RunnerImageBuild.olderBaseRefusal("1.3.0", "1.3.0", "p")).isNull();
        assertThat(RunnerImageBuild.olderBaseRefusal("1.4.0", "1.3.0", "p")).isNull();
        assertThat(RunnerImageBuild.olderBaseRefusal("1.3.0", null, "p"))
            .as("a stack with no image parameter yet has nothing to downgrade")
            .isNull();
    }

    @Test
    void anOlderCliIsToldToUpgradeNeverToBuild() {
        assertThat(ImageShowSubcommand.driftWarning("dev", "1.3.0", "1.4.0"))
            .contains("upgrade the CLI", "/releases/latest/download/install.sh | sh")
            .doesNotContain("admin image build`", "--update");
    }

    @Test
    void aNewerCliIsToldToBuild() {
        assertThat(ImageShowSubcommand.driftWarning("dev", "1.4.0", "1.3.0")).contains("run `baas --deployment dev admin image build`");
        assertThat(ImageShowSubcommand.driftWarning("dev", "1.3.0", "1.3.0")).isNull();
        assertThat(ImageShowSubcommand.driftWarning("dev", "1.3.0", null)).isNull();
    }
}
