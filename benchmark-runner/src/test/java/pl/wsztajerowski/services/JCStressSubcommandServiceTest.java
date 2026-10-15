package pl.wsztajerowski.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.wsztajerowski.options.CommonSharedOptions;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pl.wsztajerowski.options.JCStressOptionsBuilder.jcStressOptionsBuilder;

class JCStressSubcommandServiceTest {

    /**
     * A JCStress that dies before writing its report used to fail later, in the HTML parser, naming
     * a file rather than the process (finding A15). The process output is still shipped first: it
     * is the only record of why it died.
     */
    @Test
    void aProcessThatWroteNoReportFailsNamingItsExitCode(@TempDir Path tmp) {
        List<Path> saved = new ArrayList<>();
        var sut = new JCStressSubcommandService(
            (storagePath, localPath) -> saved.add(storagePath),
            measurements -> { throw new AssertionError("nothing should be stored"); },
            new CommonSharedOptions(tmp.resolve("result"), "req-1", Instant.now(), "p", Map.of()),
            tmp.resolve("absent.jar"),
            jcStressOptionsBuilder()
                .withReportPath(tmp.resolve("report"))
                .withProcessOutput(tmp.resolve("jcstress-output.txt"))
                .build());

        assertThatThrownBy(sut::executeCommand)
            .hasMessageContaining("exit code 1")
            .hasMessageContaining("index.html");
        assertThat(saved).contains(tmp.resolve("result/jcstress-output.txt"));
    }

    @Test
    void aModeReachesJCStressAsItsShortOption(@TempDir Path tmp) {
        var sut = serviceWith(tmp, "sanity");

        assertThat(sut.jcstressProcess().commands()).containsSubsequence("-m", "sanity");
    }

    /** Without --mode JCStress keeps its default, so results stay comparable with earlier jobs. */
    @Test
    void noModeAddsNoModeArgument(@TempDir Path tmp) {
        var sut = serviceWith(tmp, null);

        assertThat(sut.jcstressProcess().commands()).doesNotContain("-m");
    }

    private static JCStressSubcommandService serviceWith(Path tmp, String mode) {
        return new JCStressSubcommandService(
            (storagePath, localPath) -> { },
            measurements -> { },
            new CommonSharedOptions(tmp.resolve("result"), "req-1", Instant.now(), "p", Map.of()),
            tmp.resolve("tests.jar"),
            jcStressOptionsBuilder()
                .withReportPath(tmp.resolve("report"))
                .withProcessOutput(tmp.resolve("jcstress-output.txt"))
                .withMode(mode)
                .build());
    }
}
