package pl.wsztajerowski.baas.console;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PrintableTest {

    @Test
    void controlCharactersAreRemovedAndTabIsKept() {
        assertThat(Printable.of("a\u001b[31mb\u0007c\td\u009be\u007ff\nz")).isEqualTo("a[31mbc\tdefz");
    }

    @Test
    void printableTextIsUnchanged() {
        assertThat(Printable.of("Intel(R) Xeon(R) · 3.00GHz — ✓")).isEqualTo("Intel(R) Xeon(R) · 3.00GHz — ✓");
        assertThat(Printable.of(null)).isNull();
    }
}
