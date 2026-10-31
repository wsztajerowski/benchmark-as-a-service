package pl.wsztajerowski.baas.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every spelling here was observed against the fixture JARs (the change's {@code option-lists.md}):
 * these are the tool's readings, not ours.
 */
class BenchmarkArgsTest {

    private static final BenchmarkArgs JMH = BenchmarkArgs.of(BenchmarkType.JMH);
    private static final BenchmarkArgs JCSTRESS = BenchmarkArgs.of(BenchmarkType.JCSTRESS);

    private static List<String> args(String line) {
        return Arrays.asList(line.split(" "));
    }

    @ParameterizedTest
    @CsvSource({
        "MyBenchmark -rf csv, -rf",
        "MyBenchmark -rff x.json, -rff",
        "MyBenchmark -o out.txt, -o",
        "MyBenchmark --rf=csv, -rf",
        "MyBenchmark -rf=csv, -rf",
        "MyBenchmark -oout.txt, -o",
        "MyBenchmark -prof async:dir=/tmp/x, dir in -prof async",
        "MyBenchmark -pro async:dir=/tmp/x, dir in -prof async",
        "MyBenchmark --prof=async:libPath=/x.so, libPath in -prof async",
        "MyBenchmark -prof jfr:-dir=/tmp/x, dir in -prof jfr",
        "MyBenchmark -prof async:event=wall;dir=/tmp/x, dir in -prof async",
    })
    void aManagedArgumentIsFoundInEverySpellingJmhAccepts(String line, String expected) {
        assertThat(JMH.firstManaged(args(line))).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "MyBenchmark -r 1s -f 1",
        "MyBenchmark -p size=10,100",
        "MyBenchmark -jvmArgs -rf",
        "MyBenchmark -prof gc -prof async:event=wall;output=flamegraph",
        "MyBenchmark -prof jfr:configName=profile",
        "MyBenchmark -v EXTRA -wi 1 -i 3",
        "MyBenchmark -- -rf",
    })
    void ordinaryJmhArgumentsAreNeitherManagedNorNoRun(String line) {
        assertThat(JMH.firstManaged(args(line))).isEmpty();
        assertThat(JMH.firstNoRun(args(line))).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "-h, -h",
        "-l, -l",
        "-lp, -lp",
        "-lprof, -lprof",
        "-lrf, -lrf",
        "-lr, -lrf",
        "-lpro, -lprof",
        "-hl, -h",
        "--lprof, -lprof",
        "MyBenchmark -f 1 -l, -l",
    })
    void aNoRunArgumentIsFoundInEverySpellingJmhAccepts(String line, String expected) {
        assertThat(JMH.firstNoRun(args(line))).contains(expected);
    }

    @Test
    void anAmbiguousPrefixIsAClusterAndNotAGuardedOption() {
        // -jvmA begins jvmArgs, jvmArgsAppend and jvmArgsPrepend: jopt-simple then reads a cluster,
        // and `j` is no option — JMH rejects it itself.
        assertThat(JMH.read(args("-jvmA x"))).isEmpty();
    }

    @Test
    void theValueOfARequiredValueOptionIsNeverReadAsAnOption() {
        assertThat(JMH.read(args("-jvmArgs -h -f 1")))
            .extracting(BenchmarkArgs.Occurrence::name)
            .containsExactly("jvmArgs", "f");
    }

    @Test
    void jcstressRefusesItsReportDirectoryAndItsNoRunModes() {
        assertThat(JCSTRESS.firstManaged(args("-r /tmp/report"))).contains("-r");
        assertThat(JCSTRESS.firstManaged(args("-rx"))).contains("-r");
        assertThat(JCSTRESS.firstNoRun(args("-h"))).contains("-h");
        assertThat(JCSTRESS.firstNoRun(args("-l"))).contains("-l");
        assertThat(JCSTRESS.firstNoRun(args("-p old.bin.gz"))).contains("-p");
    }

    @Test
    void jcstressModeAndSelectionPass() {
        assertThat(JCSTRESS.firstManaged(args("-m sanity -t Foo -f 1"))).isEmpty();
        assertThat(JCSTRESS.firstNoRun(args("-m sanity -t Foo -f 1"))).isEmpty();
    }

    @Test
    void anOptionalValueIsTakenOnlyWhenTheNextTokenIsNoOption() {
        assertThat(JCSTRESS.read(args("-f 2 -sc -m sanity")))
            .extracting(BenchmarkArgs.Occurrence::name, BenchmarkArgs.Occurrence::value)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("f", "2"),
                org.assertj.core.groups.Tuple.tuple("sc", null),
                org.assertj.core.groups.Tuple.tuple("m", "sanity"));
    }

    @Test
    void profilersAreReadInEverySpellingAndTaggedSortedWithoutOptions() {
        var line = args("MyBenchmark -prof gc -pro async:event=alloc --prof=gc -pr stack");
        assertThat(JMH.profilers(line)).extracting(BenchmarkArgs.Profiler::name)
            .containsExactly("gc", "async", "gc", "stack");
        assertThat(JMH.profilersTag(line)).contains("async,gc,stack");
    }

    @Test
    void theOrderProfilersAreGivenInDoesNotChangeTheTag() {
        assertThat(JMH.profilersTag(args("-prof gc -prof async")))
            .isEqualTo(JMH.profilersTag(args("-prof async -prof gc")));
    }

    @Test
    void anUnprofiledJobHasNoProfilersTag() {
        assertThat(JMH.profilersTag(args("MyBenchmark -f 1"))).isEmpty();
        assertThat(JCSTRESS.profilersTag(args("-m sanity"))).isEmpty();
    }

    @Test
    void aClassNamedProfilerIsRecordedAsWrittenUnlessBaasManagesIt() {
        assertThat(JMH.profilersTag(args("-prof org.openjdk.jmh.profile.GCProfiler")))
            .contains("org.openjdk.jmh.profile.GCProfiler");
        assertThat(JMH.profilersTag(args("-prof org.openjdk.jmh.profile.AsyncProfiler -prof jfr")))
            .contains("async,jfr");
    }

    /** JMH resolves a profiler by its class name too; written so, its managed keys are still BaaS's. */
    @Test
    void aClassNamedAsyncOrJfrIsManagedLikeItsShortName() {
        assertThat(JMH.firstManaged(args("-prof org.openjdk.jmh.profile.JavaFlightRecorderProfiler:dir=/tmp")))
            .contains("dir in -prof jfr");
        var rewritten = JMH.withProfilerOptions(args("-prof org.openjdk.jmh.profile.AsyncProfiler"),
            profiler -> profiler.canonicalName().equals("async") ? "dir=/d" : null);
        assertThat(rewritten).containsExactly("-prof", "org.openjdk.jmh.profile.AsyncProfiler:dir=/d");
    }

    @Test
    void profilerOptionsAreAppendedWhereTheValueWasWritten() {
        var line = args("MyBenchmark -prof async -pro=jfr -prof gc --prof async:event=wall;threads=true");
        var rewritten = JMH.withProfilerOptions(line, profiler -> switch (profiler.name()) {
            case "async" -> "dir=/app/p;libPath=/a.so";
            case "jfr" -> "dir=/app/p";
            default -> null;
        });
        assertThat(rewritten).containsExactly(
            "MyBenchmark",
            "-prof", "async:dir=/app/p;libPath=/a.so",
            "-pro=jfr:dir=/app/p",
            "-prof", "gc",
            "--prof", "async:event=wall;threads=true;dir=/app/p;libPath=/a.so");
    }

    @Test
    void anEmptyOptionStringOrATrailingSeparatorGainsNoEmptyPiece() {
        assertThat(BenchmarkArgs.Profiler.parse("async:").withOptions("dir=d")).isEqualTo("async:dir=d");
        assertThat(BenchmarkArgs.Profiler.parse("async:event=wall;").withOptions("dir=d"))
            .isEqualTo("async:event=wall;dir=d");
    }
}
