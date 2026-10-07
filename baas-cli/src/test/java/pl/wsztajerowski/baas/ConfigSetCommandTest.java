package pl.wsztajerowski.baas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import pl.wsztajerowski.baas.config.ConfigService;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code config set} writes the selected deployment's own file and nothing else. */
class ConfigSetCommandTest {

    @Test
    void writesTheOnlyDeploymentsFile(@TempDir Path dir) throws Exception {
        Path file = TestDeployments.write(dir, TestDeployments.DEFAULT);

        int exit = execute(dir, "config", "set", "--instance-type", "c6i.large");

        assertThat(exit).isZero();
        assertThat(Files.readString(file)).contains("c6i.large");
    }

    @Test
    void writesOnlyTheNamedDeploymentsFile(@TempDir Path dir) throws Exception {
        Path main = TestDeployments.write(dir, TestDeployments.DEFAULT);
        Path dev = TestDeployments.write(dir, "wiktor-dev");
        String mainBefore = Files.readString(main);

        int exit = execute(dir, "--deployment", "wiktor-dev", "config", "set", "--instance-type", "c6i.large");

        assertThat(exit).isZero();
        assertThat(Files.readString(dev)).contains("c6i.large");
        assertThat(Files.readString(main)).isEqualTo(mainBefore);
    }

    @Test
    void withNoDeploymentConfiguredNothingIsWritten(@TempDir Path dir) {
        int exit = execute(dir, "config", "set", "--instance-type", "c6i.large");

        assertThat(exit).isNotZero();
        assertThat(dir.resolve("deployments")).doesNotExist();
    }

    @Test
    void gitDerivationCanBeTurnedBackOff(@TempDir Path dir) throws Exception {
        TestDeployments.write(dir, TestDeployments.DEFAULT);
        execute(dir, "config", "set", "--git-resolve-project", "true");

        execute(dir, "config", "set", "--git-resolve-project", "false");

        assertThat(new ConfigService(dir, Optional.empty()).load().getGit().isResolveProject()).isFalse();
    }

    @Test
    void configSetRefusesAMarginBelowTheFloor(@TempDir Path dir) throws Exception {
        Path file = TestDeployments.write(dir, TestDeployments.DEFAULT);
        String before = Files.readString(file);

        int exit = execute(dir, "config", "set", "--watchdog-margin", "10");

        assertThat(exit).isNotZero();
        assertThat(Files.readString(file)).isEqualTo(before);
    }

    @Test
    void theAbsoluteWallClockOptionIsGoneFromConfigSet(@TempDir Path dir) {
        assertThat(execute(dir, "config", "set", "--max-wall-clock", "9000")).isEqualTo(2);
    }

    private static int execute(Path root, String... args) {
        return new CommandLine(new BaasApp(root))
            .setOut(new PrintWriter(new StringWriter()))
            .setErr(new PrintWriter(new StringWriter()))
            .execute(args);
    }
}
