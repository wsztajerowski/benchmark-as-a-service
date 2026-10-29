package pl.wsztajerowski.baas.console;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static pl.wsztajerowski.baas.console.LogStyle.Mode.FULL;
import static pl.wsztajerowski.baas.console.LogStyle.Mode.SHORT;
import static pl.wsztajerowski.baas.console.LogStyle.Mode.SHORT_COLOURED;

class LogStyleTest {

    private static final Map<String, String> TERM = Map.of("TERM", "xterm-256color");

    @Test
    void aTerminalOnBothStreamsGetsShortColouredLines() {
        assertThat(LogStyle.decide(true, true, TERM)).isEqualTo(SHORT_COLOURED);
    }

    @Test
    void noColourKeepsTheShortFormat() {
        assertThat(LogStyle.decide(true, true, Map.of("TERM", "xterm", "NO_COLOR", "1"))).isEqualTo(SHORT);
    }

    /** `baas run … 2> run.log` on a terminal: the file keeps its timestamps. */
    @Test
    void standardErrorElsewhereKeepsTheFullFormat() {
        assertThat(LogStyle.decide(true, false, TERM)).isEqualTo(FULL);
    }

    @Test
    void noTerminalOrADumbOneKeepsTheFullFormat() {
        assertThat(LogStyle.decide(false, true, TERM)).isEqualTo(FULL);
        assertThat(LogStyle.decide(true, true, Map.of("TERM", "dumb"))).isEqualTo(FULL);
    }

    private static String through(String... writes) {
        var bytes = new ByteArrayOutputStream();
        PrintStream err = LogStyle.colouring(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        for (String write : writes) {
            if (write == null) {
                err.flush();
            } else {
                err.print(write);
            }
        }
        err.flush();
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void eachLevelGetsMavensColour() {
        assertThat(through("ERROR boom\n")).isEqualTo("\u001b[1;31mERROR\u001b[0m boom\n");
        assertThat(through("WARN careful\n")).isEqualTo("\u001b[1;33mWARN\u001b[0m careful\n");
        assertThat(through("INFO started\n")).isEqualTo("\u001b[1;34mINFO\u001b[0m started\n");
        assertThat(through("DEBUG detail\n")).isEqualTo("\u001b[1;36mDEBUG\u001b[0m detail\n");
    }

    @Test
    void onlyTheLevelWordIsColoured() {
        assertThat(through("WARN INFO is in the message\n"))
            .isEqualTo("\u001b[1;33mWARN\u001b[0m INFO is in the message\n");
    }

    @Test
    void lineThatIsNotALogLineIsUntouched() {
        assertThat(through("Next\n", "  measurements  baas query --job-id x\n", "\tat pl.Foo.bar(Foo.java:1)\n"))
            .isEqualTo("Next\n  measurements  baas query --job-id x\n\tat pl.Foo.bar(Foo.java:1)\n");
    }

    /** A flush writes what it holds: coloured if the level word is complete, as is if it was cut. */
    @Test
    void aLineFlushedBeforeItsNewlineIsStillWritten() {
        assertThat(through("WARN half", null, " rest\n", "INFO next\n"))
            .isEqualTo("\u001b[1;33mWARN\u001b[0m half rest\n\u001b[1;34mINFO\u001b[0m next\n");
        assertThat(through("WA", null, "RN cut\n", "INFO next\n"))
            .isEqualTo("WARN cut\n\u001b[1;34mINFO\u001b[0m next\n");
    }
}
