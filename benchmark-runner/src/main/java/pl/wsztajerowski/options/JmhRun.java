package pl.wsztajerowski.options;

import pl.wsztajerowski.baas.model.BenchmarkType;

import java.nio.file.Path;
import java.util.List;

/**
 * One JMH job as the runner starts it: the benchmark JAR, the arguments that follow {@code baas run
 * jmh … --} exactly as the user wrote them, the directory everything the runner manages is written
 * to, the baked async-profiler library a {@code -prof async} is pointed at, and the {@code java} the
 * benchmark process starts with — JMH's forks follow its {@code java.home}.
 */
public record JmhRun(Path benchmarkPath, List<String> arguments, Path workDir, Path asyncPath, String java) {

    public JmhRun {
        arguments = List.copyOf(arguments);
    }

    /** A run on {@code java} from {@code PATH}. */
    public JmhRun(Path benchmarkPath, List<String> arguments, Path workDir, Path asyncPath) {
        this(benchmarkPath, arguments, workDir, asyncPath, "java");
    }

    /** {@code -rff}: JMH's machine-readable result, read back to store the measurements. */
    public Path resultFile() {
        return workDir.resolve("jmh-results.json");
    }

    /** Where the process's output is redirected as it runs. */
    public Path processOutput() {
        return workDir.resolve(BenchmarkType.JMH.workingOutputName());
    }

    /**
     * The {@code dir} every {@code async} and {@code jfr} profiler is given: JMH writes one directory
     * per benchmark below it, and the whole tree is uploaded to the job's prefix.
     */
    public Path profilerOutput() {
        return workDir.resolve("profiler-output");
    }
}
