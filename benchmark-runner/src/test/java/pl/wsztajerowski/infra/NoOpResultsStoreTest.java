package pl.wsztajerowski.infra;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;

class NoOpResultsStoreTest {

    @Test
    void discardsWithoutThrowing() {
        assertThatCode(() -> new NoOpResultsStore().write(List.of()))
            .doesNotThrowAnyException();
    }

    @Test
    void toleratesANullListRatherThanFailingLate() {
        assertThatCode(() -> new NoOpResultsStore().write(null))
            .doesNotThrowAnyException();
    }
}
