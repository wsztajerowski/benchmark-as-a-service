package pl.wsztajerowski.commands;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import pl.wsztajerowski.baas.model.BenchmarkArgs;
import pl.wsztajerowski.baas.model.BenchmarkType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BenchmarkArgs} holds each tool's option names and arities so that {@code baas-model} needs
 * no benchmark library; this pins them to what the fixture JARs' own {@code -h} lists. Bumping JMH or
 * JCStress fails here until the lists follow — the refusals, the {@code -prof} injection and the
 * {@code profilers} tag all read through those lists, and a stale one reads an argument wrongly.
 */
class BenchmarkArgsContractIT {

    /** {@code  -name <value>}, {@code  -name [value]} or {@code  -name} at the start of a help line. */
    private static final Pattern OPTION = Pattern.compile("^ {2}-(\\S+)(?: +([<\\[]))?");

    @ParameterizedTest
    @CsvSource({"JMH, target/fake-jmh-benchmarks.jar", "JCSTRESS, target/fake-stress-tests.jar"})
    void theOptionListsAreTheToolsOwn(BenchmarkType type, Path jar) throws IOException, InterruptedException {
        Process help = new ProcessBuilder("java", "-jar", jar.toAbsolutePath().toString(), "-h")
            .redirectErrorStream(true)
            .start();
        String text = new String(help.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        help.waitFor();

        Map<String, BenchmarkArgs.Arity> listed = new TreeMap<>();
        for (String line : text.split("\\R")) {
            var matcher = OPTION.matcher(line);
            if (matcher.find()) {
                String bracket = matcher.group(2);
                listed.put(matcher.group(1), bracket == null ? BenchmarkArgs.Arity.NONE
                    : bracket.equals("<") ? BenchmarkArgs.Arity.REQUIRED : BenchmarkArgs.Arity.OPTIONAL);
            }
        }

        assertThat(listed).as(type + "'s -h").isNotEmpty();
        assertThat(new TreeMap<>(BenchmarkArgs.of(type).options())).isEqualTo(listed);
    }
}
