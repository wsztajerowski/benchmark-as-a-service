package pl.wsztajerowski.baas.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdksTest {

    @Test
    void theDefaultIsAValidId() {
        assertThat(Jdks.isValidId(Jdks.DEFAULT_ID)).isTrue();
    }

    @Test
    void idsAreLowerCaseNamesWithDotsAndDashes() {
        assertThat(Jdks.isValidId("temurin-26")).isTrue();
        assertThat(Jdks.isValidId("zulu-21.0.5")).isTrue();
        assertThat(Jdks.isValidId("21")).isTrue();
    }

    @Test
    void anIdCannotLeaveTheRootOrBreakATagList() {
        for (String bad : new String[]{"Temurin_26", "..", ".hidden", "-x", "a/b", "a,b", "a b", "", null}) {
            assertThat(Jdks.isValidId(bad)).as(bad).isFalse();
        }
    }

    @Test
    void anIdIsAtMostThirtyTwoCharacters() {
        assertThat(Jdks.isValidId("a".repeat(32))).isTrue();
        assertThat(Jdks.isValidId("a".repeat(33))).isFalse();
    }
}
