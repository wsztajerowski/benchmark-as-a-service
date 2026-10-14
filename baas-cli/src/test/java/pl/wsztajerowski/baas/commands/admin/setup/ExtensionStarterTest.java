package pl.wsztajerowski.baas.commands.admin.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.wsztajerowski.baas.infra.RunnerImageExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Setup leaves the operator a starting point for extending the runner image. */
class ExtensionStarterTest {

    @TempDir
    Path dir;

    @Test
    void setupWritesTheStarterWhenNoneExists() throws Exception {
        Path file = dir.resolve(DoneStep.EXTENSION_STARTER_FILE);

        DoneStep.writeExtensionStarter(file, "dev");

        assertThat(Files.readString(file))
            .as("the same document a pull of a deployment with no extension prints")
            .isEqualTo(RunnerImageExtension.withMarker(""))
            .startsWith("# baas-extension-base: none");
        assertThat(RunnerImageExtension.parse(Files.readString(file)).content())
            .as("pushed unchanged, the starter deploys nothing")
            .isEmpty();
    }

    @Test
    void setupNeverOverwritesAnEditedExtensionFile() throws Exception {
        Path file = dir.resolve(DoneStep.EXTENSION_STARTER_FILE);
        String edited = "# baas-extension-base: none\nphases: []\n";
        Files.writeString(file, edited);

        DoneStep.writeExtensionStarter(file, "dev");

        assertThat(Files.readString(file)).isEqualTo(edited);
    }
}
