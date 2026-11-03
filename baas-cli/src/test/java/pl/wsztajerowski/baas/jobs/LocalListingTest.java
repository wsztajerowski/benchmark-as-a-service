package pl.wsztajerowski.baas.jobs;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The listing's names as the two tools print them, captured from the fixture JARs on 2026-11-03. */
class LocalListingTest {

    private static final String JMH = """
        Benchmarks:\s
        pl.wsztajerowski.fake.Incrementing_Synchronized.incrementUsingSynchronized
        pl.wsztajerowski.fake.Summing_Array.sum
        """;

    private static final String JCSTRESS = """
        Java Concurrency Stress Tests
        ---------------------------------------------------------------------------------
        Rev: null, built by null with null at null

        Burning up to figure out the exact CPU count....... done!

        pl.wsztajerowski.IntegerIncrementing.TestWithForbiddenResults
        pl.wsztajerowski.IntegerIncrementing.TestWithInterestingResults
        """;

    @Test
    void jmhNamesFollowItsHeader() {
        assertThat(new LocalListing.Result(0, JMH).names()).containsExactly(
            "pl.wsztajerowski.fake.Incrementing_Synchronized.incrementUsingSynchronized",
            "pl.wsztajerowski.fake.Summing_Array.sum");
    }

    @Test
    void jcstressBannerAndProbeAreNotNames() {
        assertThat(new LocalListing.Result(0, JCSTRESS).names()).hasSize(2)
            .allMatch(name -> name.startsWith("pl.wsztajerowski.IntegerIncrementing."));
    }

    /** Both tools exit 0 when nothing matches: only the missing names say so. */
    @Test
    void anEmptyMatchHasNoNames() {
        assertThat(new LocalListing.Result(0, "Benchmarks: \n").names()).isEmpty();
        assertThat(new LocalListing.Result(0, JCSTRESS.lines().limit(5).map(l -> l + "\n").reduce("", String::concat))
            .names()).isEmpty();
    }

    @Test
    void jvmWarningsAreNotNames() {
        String output = "WARNING: A terminally deprecated method in sun.misc.Unsafe has been called\n" + JMH;
        assertThat(new LocalListing.Result(0, output).names()).hasSize(2);
    }

    @Test
    void theListingFlagFollowsTheArgumentsOnThisJvm() {
        assertThat(LocalListing.command(Path.of("/jdk"), Path.of("b.jar"), List.of("Summing", "-f", "1")))
            .containsExactly(Path.of("/jdk/bin/java").toString(), "-jar", "b.jar", "Summing", "-f", "1", "-l");
    }

    @Test
    void aKilledListingIsATimeout() {
        assertThat(new LocalListing.Result(-1, "").timedOut()).isTrue();
        assertThat(new LocalListing.Result(1, "").timedOut()).isFalse();
    }
}
