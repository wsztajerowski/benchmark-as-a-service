package pl.wsztajerowski.baas.console;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The detection rule every terminal effect hangs on. The environment is passed in, so these run
 * without a terminal; what the JVM reports on a real one is the design's spike, not a unit test.
 */
class ConsoleTest {

    private static final String ESC = "\u001b";

    private static Console console(boolean terminal, Map<String, String> environment) {
        return Console.of(new PrintWriter(new StringWriter()), terminal, environment);
    }

    @Test
    void aTerminalIsInteractiveAndColoured() {
        var console = console(true, Map.of("TERM", "xterm-256color"));

        assertThat(console.interactive()).isTrue();
        assertThat(console.bold("x")).contains(ESC);
    }

    @Test
    void noTerminalMeansNeitherRedrawNorColour() {
        var console = console(false, Map.of("TERM", "xterm-256color"));

        assertThat(console.interactive()).isFalse();
        assertThat(console.bold("x")).isEqualTo("x");
        assertThat(console.openStatusLine()).isNull();
    }

    /** picocli's {@code Ansi.AUTO} gets this one wrong, which is why it is not used. */
    @Test
    void aDumbTerminalIsNotInteractive() {
        var console = console(true, Map.of("TERM", "dumb"));

        assertThat(console.interactive()).isFalse();
        assertThat(console.faint("n/a")).isEqualTo("n/a");
    }

    @Test
    void noColorKeepsRedrawButDropsColour() {
        var console = console(true, Map.of("TERM", "xterm", "NO_COLOR", "1"));

        assertThat(console.interactive()).isTrue();
        assertThat(console.red("x")).isEqualTo("x");
    }

    /**
     * {@code CLICOLOR_FORCE} makes {@code Ansi.AUTO} colour a pipe. Here it is not consulted at all:
     * nothing forces an escape sequence into a non-terminal.
     */
    @Test
    void clicolorForceCannotColourAPipe() {
        var console = console(false, Map.of("CLICOLOR_FORCE", "1"));

        assertThat(console.green("x")).isEqualTo("x");
    }

    @Test
    void clearingTheScreenOfAPipeIsABug() {
        var console = console(false, Map.of());

        assertThatThrownBy(console::clearScreen).isInstanceOf(IllegalStateException.class);
    }

    /** printf is Locale.ROOT, whatever the default: a comma decimal would corrupt JSON and CSV. */
    @Test
    void printfIgnoresTheDefaultLocale() {
        var out = new StringWriter();
        var original = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("pl-PL"));
            Console.plain(new PrintWriter(out)).printf("%.2f", 1.5);
        } finally {
            java.util.Locale.setDefault(original);
        }

        assertThat(out.toString()).isEqualTo("1.50");
    }

    /** Each write is flushed, so a same-line prompt reaches the terminal before input is read. */
    @Test
    void printFlushesWithoutANewline() {
        var out = new StringWriter();
        var buffered = new PrintWriter(new java.io.BufferedWriter(out));

        Console.plain(buffered).print("Type the stack name: ");

        assertThat(out.toString()).isEqualTo("Type the stack name: ");
    }
}
