package pl.wsztajerowski.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.wsztajerowski.options.CommonSharedOptions;
import pl.wsztajerowski.options.JCStressRun;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
            new JCStressRun(tmp.resolve("absent.jar"), List.of(), tmp));

        assertThatThrownBy(sut::executeCommand)
            .hasMessageContaining("exit code 1")
            .hasMessageContaining("index.html");
        assertThat(saved).contains(tmp.resolve("result/jcstress-output.txt"));
    }

    /** JCStress's own -m and -t reach it as written, after the report directory the runner sets. */
    @Test
    void theArgumentsReachJCStressAsGivenAfterTheReportDirectory(@TempDir Path tmp) {
        var sut = serviceWith(tmp, List.of("-m", "sanity", "-t", "Foo", "-f", "1"));

        assertThat(sut.jcstressProcess().commands()).containsExactly(
            "java", "-jar", tmp.resolve("tests.jar").toString(),
            "-r", tmp.resolve("jcstress-results").toString(),
            "-m", "sanity", "-t", "Foo", "-f", "1");
    }

    /** Without -m JCStress keeps its default, so results stay comparable with earlier jobs. */
    @Test
    void noModeAddsNoModeArgument(@TempDir Path tmp) {
        var sut = serviceWith(tmp, List.of());

        assertThat(sut.jcstressProcess().commands()).doesNotContain("-m");
    }

    private static JCStressSubcommandService serviceWith(Path tmp, List<String> arguments) {
        return new JCStressSubcommandService(
            (storagePath, localPath) -> { },
            measurements -> { },
            new CommonSharedOptions(tmp.resolve("result"), "req-1", Instant.now(), "p", Map.of()),
            new JCStressRun(tmp.resolve("tests.jar"), arguments, tmp));
    }
}
