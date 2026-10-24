package pl.wsztajerowski.infra;

import org.junit.jupiter.api.Test;
import pl.wsztajerowski.entities.jcstress.JCStressResult;

import java.net.URISyntaxException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parser against a real report: {@code jcstress/index.html} is what the {@code fake-stress-tests}
 * fixture writes in sanity mode (JCStress 0.16), one forbidden result failing and one interesting
 * result passing. It used to run only inside the Docker+Mongo {@code JCStressSubcommandServiceIT}
 * (review T3). The verdict it reads is what {@code baas results query} shows as {@code 1/2 ✗}.
 */
class JCStressHtmlResultParserTest {

    private static final Path PREFIX = Path.of("jobs/lynx-journal/20261015T063552646Z-dc22b1e8");

    private static JCStressResult parse() throws URISyntaxException {
        Path report = Path.of(JCStressHtmlResultParserTest.class.getResource("/jcstress/index.html").toURI());
        return JCStressHtmlResultParser.getJCStressHtmlResultParser(report, PREFIX).parse();
    }

    @Test
    void theOverallPassRateIsTheVerdict() throws Exception {
        var result = parse();

        assertThat(result.totalTests()).isEqualTo(2);
        assertThat(result.passedTests()).isEqualTo(1);
    }

    /** JCStress names only non-passing tests; each points at its own page under the job's prefix. */
    @Test
    void failedAndInterestingTestsAreNamedWithTheirPages() throws Exception {
        var result = parse();

        assertThat(result.testsWithFailedResults()).containsOnlyKeys("pl.wsztajerowski.IntegerIncrementing.TestWithForbiddenResults")
            .containsValue(PREFIX.resolve("pl.wsztajerowski.IntegerIncrementing.TestWithForbiddenResults.html").toString());
        assertThat(result.testsWithInterestingResults()).containsOnlyKeys("pl.wsztajerowski.IntegerIncrementing.TestWithInterestingResults");
        assertThat(result.testsWithErrorResults()).as("the report's ERROR section says None!").isEmpty();
    }

    @Test
    void theEnvironmentIsReadFromTheReport() throws Exception {
        var result = parse();

        assertThat(result.javaVmName()).isEqualTo("OpenJDK 64-Bit Server VM");
        assertThat(result.javaVmVersion()).isEqualTo("25+36-LTS");
        assertThat(result.osArch()).isEqualTo("x86_64");
    }
}
