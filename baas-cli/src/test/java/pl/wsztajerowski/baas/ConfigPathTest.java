package pl.wsztajerowski.baas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import pl.wsztajerowski.baas.config.ConfigService;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code --config-path} replaced the per-command {@code --results-table}/{@code --bucket} overrides.
 * Every case goes through the real command tree, because the option is inherited and only the tree
 * shows where picocli writes it.
 */
class ConfigPathTest {

    @Test
    void isHonouredBeforeTheSubcommand(@TempDir Path dir) {
        Path file = dir.resolve("other.yaml");

        assertThat(configFileSeenBy("--config-path", file.toString(), "results"))
            .isEqualTo(file);
    }

    @Test
    void isHonouredAfterTheSubcommand(@TempDir Path dir) {
        Path file = dir.resolve("other.yaml");

        assertThat(configFileSeenBy("results", "--config-path", file.toString()))
            .isEqualTo(file);
    }

    @Test
    void isHonouredAfterANestedSubcommand(@TempDir Path dir) {
        Path file = dir.resolve("other.yaml");

        assertThat(configFileSeenBy("config", "show", "--config-path", file.toString()))
            .isEqualTo(file);
    }

    @Test
    void defaultsToTheHomeDirectoryFile() {
        assertThat(configFileSeenBy("results")).isEqualTo(ConfigService.DEFAULT_PATH);
    }

    /** A typed path that does not exist is a typo far more often than an unconfigured machine. */
    @Test
    void aMissingExplicitFileFailsAReadNamingThePath(@TempDir Path dir) {
        Path missing = dir.resolve("nope.yaml");

        assertThatThrownBy(() -> ConfigService.at(missing).load())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(missing.toString());
    }

    @Test
    void aReadCommandRefusesAMissingExplicitFile(@TempDir Path dir) {
        Path missing = dir.resolve("nope.yaml");

        int exit = execute("--config-path", missing.toString(), "results", "--project", "p");

        assertThat(exit).isNotZero();
        assertThat(missing).doesNotExist();
    }

    @Test
    void configSetCreatesTheNamedFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("sub").resolve("dev.yaml");

        int exit = execute("--config-path", file.toString(), "config", "set",
            "--git-resolve-project", "true", "--watchdog-margin", "120");

        assertThat(exit).isZero();
        var config = ConfigService.at(file).load();
        assertThat(config.getGit().isResolveProject()).isTrue();
        assertThat(config.getEc2().getWatchdogMarginSeconds()).isEqualTo(120);
        assertThat(Files.readString(file)).doesNotContain("wallClockHardKillSeconds");
    }

    @Test
    void gitDerivationCanBeTurnedBackOff(@TempDir Path dir) {
        Path file = dir.resolve("c.yaml");
        execute("--config-path", file.toString(), "config", "set", "--git-resolve-project", "true");

        execute("--config-path", file.toString(), "config", "set", "--git-resolve-project", "false");

        assertThat(ConfigService.at(file).load().getGit().isResolveProject()).isFalse();
    }

    @Test
    void configSetRefusesAMarginBelowTheFloor(@TempDir Path dir) {
        Path file = dir.resolve("c.yaml");

        int exit = execute("--config-path", file.toString(), "config", "set", "--watchdog-margin", "10");

        assertThat(exit).isNotZero();
        assertThat(file).doesNotExist();
    }

    @Test
    void theAbsoluteWallClockOptionIsGoneFromConfigSet(@TempDir Path dir) {
        int exit = execute("--config-path", dir.resolve("c.yaml").toString(),
            "config", "set", "--max-wall-clock", "9000");

        assertThat(exit).isEqualTo(2);
    }

    private static Path configFileSeenBy(String... args) {
        CommandLine.ParseResult result = new CommandLine(new BaasApp()).parseArgs(args);
        while (result.hasSubcommand()) {
            result = result.subcommand();
        }
        return BaasApp.configService(result.commandSpec()).configFilePath();
    }

    private static int execute(String... args) {
        return new CommandLine(new BaasApp())
            .setOut(new PrintWriter(new StringWriter()))
            .setErr(new PrintWriter(new StringWriter()))
            .execute(args);
    }
}
