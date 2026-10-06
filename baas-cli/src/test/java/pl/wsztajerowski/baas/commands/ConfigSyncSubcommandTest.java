package pl.wsztajerowski.baas.commands;

import java.util.Map;
import java.nio.file.Path;
import java.nio.file.Files;
import pl.wsztajerowski.baas.config.ConfigService;
import pl.wsztajerowski.baas.BaasApp;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigSyncSubcommandTest {

    /**
     * Required although the prefix is derivable. A bare sync on a machine with no local state
     * would adopt whatever deployment the active credentials imply — in CI, that is a wrong role
     * or a leftover AWS_PROFILE binding the machine to another account's deployment, discovered
     * only after something has been provisioned.
     */
    @Test
    void theDeploymentNameIsRequired() {
        var err = new java.io.StringWriter();
        int exit = new CommandLine(new pl.wsztajerowski.baas.BaasApp())
            .setErr(new java.io.PrintWriter(err, true))
            .execute("config", "sync");

        assertThat(exit).isNotZero();
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
        var parsed = new CommandLine(new pl.wsztajerowski.baas.BaasApp())
            .parseArgs("config", "sync", "--deployment", "baas-123456789012-dev");

        assertThat(parsed.subcommand().subcommand().commandSpec().userObject())
            .isInstanceOf(ConfigSyncSubcommand.class);
        assertThat(pl.wsztajerowski.baas.BaasApp.deployment(parsed.subcommand().subcommand().commandSpec()))
            .contains("baas-123456789012-dev");
    }

    /**
     * A second deployment's configuration is created by sync, in the named file only. The stack
     * lookup is stood in for: the test has no stack, and the file handling is what is under test.
     */
    @Test
    void syncWritesTheNamedFileAndLeavesTheDefaultAlone(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("dev.yaml");
        String defaultBefore = Files.exists(ConfigService.DEFAULT_PATH)
            ? Files.readString(ConfigService.DEFAULT_PATH) : null;

        int exit = new CommandLine(new BaasApp(), deployment("eu-central-1", Map.of("ResultsTableName", "t")))
            .execute("--config-path", file.toString(), "config", "sync", "--deployment", "baas-123456789012-dev");

        assertThat(exit).isZero();
        assertThat(ConfigService.at(file).load().getPrefix()).isEqualTo("baas-123456789012-dev");
        assertThat(Files.exists(ConfigService.DEFAULT_PATH)
            ? Files.readString(ConfigService.DEFAULT_PATH) : null).isEqualTo(defaultBefore);
    }

    @Test
    void aMissingStackWritesNothing(@TempDir Path dir) {
        Path file = dir.resolve("dev.yaml");

        int exit = new CommandLine(new BaasApp(), deployment("eu-central-1", Map.of()))
            .execute("--config-path", file.toString(), "config", "sync", "--deployment", "baas-nope");

        assertThat(exit).as("a bucket a teardown retained is not a deployment").isNotZero();
        assertThat(file).doesNotExist();
    }

    @Test
    void noBucketMeansNoDeploymentAndWritesNothing(@TempDir Path dir) {
        Path file = dir.resolve("dev.yaml");

        int exit = new CommandLine(new BaasApp(), deployment(null, Map.of("ResultsTableName", "t")))
            .execute("--config-path", file.toString(), "config", "sync", "--deployment", "baas-nope");

        assertThat(exit).isNotZero();
        assertThat(file).doesNotExist();
    }

    /**
     * The region is found from the bucket and stored, whatever region the machine started in: the
     * deployment's region is a fact about it, so a machine or a CI job aimed elsewhere follows it.
     */
    @Test
    void syncStoresTheRegionTheDeploymentLivesIn(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("c.yaml");
        Files.writeString(file, "aws:\n  region: \"eu-west-1\"\n");

        int exit = new CommandLine(new BaasApp(), deployment("us-east-1", Map.of("ResultsTableName", "t")))
            .execute("--config-path", file.toString(), "config", "sync", "--deployment", "baas-123456789012-dev");

        assertThat(exit).isZero();
        assertThat(ConfigService.at(file).load().getAws().getRegion()).isEqualTo("us-east-1");
    }

    /** A bucket in {@code bucketRegion} (none when null) and a stack there with {@code outputs}. */
    private static CommandLine.IFactory deployment(String bucketRegion, Map<String, String> outputs) {
        CommandLine.IFactory defaults = CommandLine.defaultFactory();
        return new CommandLine.IFactory() {
            @Override
            @SuppressWarnings("unchecked")
            public <K> K create(Class<K> cls) throws Exception {
                if (cls == ConfigSyncSubcommand.class) {
                    return (K) new ConfigSyncSubcommand() {
                        @Override
                        java.util.Optional<String> bucketRegion(pl.wsztajerowski.baas.config.BaasConfig config) {
                            return java.util.Optional.ofNullable(bucketRegion);
                        }

                        @Override
                        Map<String, String> stackOutputs(pl.wsztajerowski.baas.config.BaasConfig config, String region) {
                            return region.equals(bucketRegion) ? outputs : Map.of();
                        }
                    };
                }
                return defaults.create(cls);
            }
        };
    }
}
