package pl.wsztajerowski.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.wsztajerowski.services.options.CommonSharedOptions;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pl.wsztajerowski.services.options.JCStressOptionsBuilder.jcStressOptionsBuilder;

class JCStressSubcommandServiceTest {

    /**
     * A JCStress that dies before writing its report used to fail later, in the HTML parser, naming
     * a file rather than the process (finding A15). The process output is still shipped first: it
     * is the only record of why it died.
     */
    @Test
    void aProcessThatWroteNoReportFailsNamingItsExitCode(@TempDir Path tmp) {
        List<Path> saved = new ArrayList<>();
        var sut = JCStressSubcommandServiceBuilder.serviceBuilder()
            .withBenchmarkPath(tmp.resolve("absent.jar"))
            .withCommonOptions(new CommonSharedOptions(tmp.resolve("result"), "req-1", Instant.now(), "p", Map.of()))
            .withStorageService((storagePath, localPath) -> saved.add(storagePath))
            .withResultsStore(measurements -> { throw new AssertionError("nothing should be stored"); })
            .withJCStressOptions(jcStressOptionsBuilder()
                .withReportPath(tmp.resolve("report"))
                .withProcessOutput(tmp.resolve("jcstress-output.txt"))
                .build())
            .build();

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

    /** Without --mode JCStress keeps its default, so results stay comparable with earlier runs. */
    @Test
    void noModeAddsNoModeArgument(@TempDir Path tmp) {
        var sut = serviceWith(tmp, null);

        assertThat(sut.jcstressProcess().commands()).doesNotContain("-m");
    }

    private static JCStressSubcommandService serviceWith(Path tmp, String mode) {
        return JCStressSubcommandServiceBuilder.serviceBuilder()
            .withBenchmarkPath(tmp.resolve("tests.jar"))
            .withCommonOptions(new CommonSharedOptions(tmp.resolve("result"), "req-1", Instant.now(), "p", Map.of()))
            .withStorageService((storagePath, localPath) -> { })
            .withResultsStore(measurements -> { })
            .withJCStressOptions(jcStressOptionsBuilder()
                .withReportPath(tmp.resolve("report"))
                .withProcessOutput(tmp.resolve("jcstress-output.txt"))
                .withMode(mode)
                .build())
            .build();
    }
}
