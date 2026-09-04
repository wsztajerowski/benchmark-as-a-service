package pl.wsztajerowski.baas.commands.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;
import pl.wsztajerowski.baas.BaasApp;
import pl.wsztajerowski.baas.config.BaasConfig;

import static org.assertj.core.api.Assertions.assertThat;

class ImageCommandsTest {

    @ParameterizedTest
    @ValueSource(strings = {"build-image", "image"})
    void resolvesUnderAdmin(String subcommand) {
        CommandLine.ParseResult result = new CommandLine(new BaasApp())
            .parseArgs("admin", subcommand, "--help");

        assertThat(result.subcommand().commandSpec().name()).isEqualTo("admin");
        assertThat(result.subcommand().subcommand().commandSpec().name()).isEqualTo(subcommand);
    }

    /**
     * Building an image is a deployer operation — it needs {@code imagebuilder:*},
     * {@code ssm:PutParameter} on the pointer and a widened {@code iam:PassRole}. Resolving it from
     * {@code aws.operatorProfile} would either fail confusingly or, worse, work because someone
     * had pointed that field at deployer credentials.
     */
    @ParameterizedTest
    @ValueSource(strings = {"build-image", "image"})
    void isNotResolvableAsATopLevelCommand(String subcommand) {
        assertThat(new CommandLine(new BaasApp()).getSubcommands())
            .as("these sit under admin precisely because they need deployer credentials")
            .doesNotContainKey(subcommand);
    }

    /**
     * Both commands read {@code aws.profile} directly rather than
     * {@link BaasConfig.AwsConfig#resolveOperatorProfile()}, which is the accessor the day-to-day
     * commands use. The two fields must not be confusable.
     */
    @Test
    void deployerProfileIsTheOneTheseCommandsRead() {
        var aws = new BaasConfig.AwsConfig();
        aws.setProfile("baas-deployer");
        aws.setOperatorProfile("baas-operator");

        assertThat(aws.getProfile())
            .as("`baas admin build-image` builds its clients from this field")
            .isEqualTo("baas-deployer");
        assertThat(aws.resolveOperatorProfile())
            .as("and never from this one, which cannot reach imagebuilder or the pointer")
            .isEqualTo("baas-operator");
    }

    @ParameterizedTest
    @ValueSource(strings = {"build-image", "image"})
    void verboseIsAcceptedSoTheArgvPreScanApplies(String subcommand) {
        CommandLine.ParseResult result = new CommandLine(new BaasApp())
            .parseArgs("admin", subcommand, "-v");

        assertThat(result.subcommand().subcommand().commandSpec().name()).isEqualTo(subcommand);
    }

    @Test
    void adminListsBothImageCommands() {
        assertThat(new CommandLine(new BaasApp()).getSubcommands().get("admin").getSubcommands())
            .containsKeys("build-image", "image");
    }
}
