package pl.wsztajerowski.baas.jobs;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --dry-run}'s reading of the real listings, against the fixture JARs the build copies into
 * {@code target/}. A JMH or JCStress bump that changes the listing's shape fails here, as
 * {@code BenchmarkArgsContractIT} fails for a changed option list.
 */
class LocalListingIT {

    private static LocalListing.Result list(String jar, String args) throws Exception {
        Path path = Path.of("target", jar);
        assertThat(path).as("copied at pre-integration-test").satisfies(p -> assertThat(Files.isRegularFile(p)).isTrue());
        List<String> arguments = args.isBlank() ? List.of() : Arrays.asList(args.split(" "));
        return LocalListing.run(LocalListing.command(Path.of(System.getProperty("java.home")), path, arguments));
    }

    @ParameterizedTest(name = "{0} {1} lists {2}")
    @CsvSource(delimiter = '|', value = {
        "fake-jmh-benchmarks.jar | -f 1 -wi 1    | 2",
        "fake-jmh-benchmarks.jar | Summing_Array | 1",
        "fake-stress-tests.jar   | -c 2          | 2",
        "fake-stress-tests.jar   | -t TestWithForbidden | 1"})
    void validArgumentsListTheirMatches(String jar, String args, int count) throws Exception {
        var result = list(jar, args);

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.names()).hasSize(count).allMatch(name -> name.startsWith("pl.wsztajerowski."));
    }

    @ParameterizedTest(name = "{0} {1} matches nothing, exit 0")
    @CsvSource(delimiter = '|', value = {
        "fake-jmh-benchmarks.jar | NoSuchBenchmark",
        "fake-stress-tests.jar   | -t NoSuchTest"})
    void aPatternThatMatchesNothingExitsZeroWithNoNames(String jar, String args) throws Exception {
        var result = list(jar, args);

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.names()).isEmpty();
    }

    @ParameterizedTest(name = "{0} {1} is rejected")
    @CsvSource(delimiter = '|', value = {
        "fake-jmh-benchmarks.jar | -f 1 -wii 1",
        "fake-stress-tests.jar   | -ff 1"})
    void aMistypedOptionExitsNonZero(String jar, String args) throws Exception {
        assertThat(list(jar, args).exitCode()).isNotZero();
    }
}
