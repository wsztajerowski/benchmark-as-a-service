package pl.wsztajerowski.baas.model;

import java.util.Arrays;
import java.util.List;

/**
 * The benchmark types: what {@code baas run <type>} accepts, the {@code benchmark-runner}
 * subcommand that executes it, the file its process output is written to and the name it is uploaded
 * as. One list, so the CLI
 * cannot accept a type the runner does not have. The names are also compile-time constants, because
 * the runner's picocli {@code @Command(name = ...)} annotations need constants.
 *
 * <p>One type per library: profiling is a JMH argument ({@code -prof}), not a type. The profiled
 * types {@code jmh-with-async} and {@code jmh-with-prof} were retired by
 * {@code benchmark-args-pass-through}; their jobs stay in storage under
 * {@link #LEGACY_PROCESS_OUTPUT_NAMES}.
 */
public enum BenchmarkType {
    JMH(BenchmarkType.JMH_NAME, "jmh-output.txt", "jmh-output.txt"),
    JCSTRESS(BenchmarkType.JCSTRESS_NAME, "jcstress-output.txt", "jcstress-output.txt");

    public static final String JMH_NAME = "jmh";
    public static final String JCSTRESS_NAME = "jcstress";

    /**
     * The process-output names the retired profiled types uploaded as. A stored job of either type
     * still holds one, and a reader grouping a job's artifacts has to recognise it.
     */
    public static final List<String> LEGACY_PROCESS_OUTPUT_NAMES =
        List.of("jmh-with-async-output.txt", "jmh-profiler-output.txt");

    private final String commandName;
    private final String workingOutputName;
    private final String processOutputName;

    BenchmarkType(String commandName, String workingOutputName, String processOutputName) {
        this.commandName = commandName;
        this.workingOutputName = workingOutputName;
        this.processOutputName = processOutputName;
    }

    /** The type {@code baas run <commandName>} names. */
    public static BenchmarkType fromCommandName(String commandName) {
        return Arrays.stream(values())
            .filter(type -> type.commandName.equals(commandName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("unknown benchmark type: " + commandName));
    }

    /** The {@code baas run} argument and the runner subcommand. */
    public String commandName() {
        return commandName;
    }

    /**
     * The file, in the runner's working directory, that the benchmark process's output is written
     * to as it runs — the runner's default, and what the instance ships when a job is stopped early.
     */
    public String workingOutputName() {
        return workingOutputName;
    }

    /** The file the runner uploads the benchmark process's output as, inside the job's prefix. */
    public String processOutputName() {
        return processOutputName;
    }

    /** Every command name, in declaration order. */
    public static List<String> commandNames() {
        return Arrays.stream(values()).map(BenchmarkType::commandName).toList();
    }
}
