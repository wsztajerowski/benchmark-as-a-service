package pl.wsztajerowski.options;

import pl.wsztajerowski.baas.model.BenchmarkType;

import java.nio.file.Path;
import java.util.List;

/**
 * One JCStress job as the runner starts it: the test JAR, the arguments that follow {@code baas run
 * jcstress … --} exactly as the user wrote them, and the directory the report and process output
 * are written to.
 */
public record JCStressRun(Path benchmarkPath, List<String> arguments, Path workDir) {

    public JCStressRun {
        arguments = List.copyOf(arguments);
    }

    /** {@code -r}: JCStress's report, parsed to store the job's summary. */
    public Path reportPath() {
        return workDir.resolve("jcstress-results");
    }

    /** Where the process's output is redirected as it runs. */
    public Path processOutput() {
        return workDir.resolve(BenchmarkType.JCSTRESS.workingOutputName());
    }
}
