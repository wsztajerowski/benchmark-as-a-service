package pl.wsztajerowski.baas.model;

import java.util.Arrays;
import java.util.List;

/**
 * The benchmark types: what {@code baas run <type>} accepts, the {@code benchmark-runner}
 * subcommand that executes it, the file its process output is written to and the name it is uploaded
 * as. One list, so the CLI
 * cannot accept a type the runner does not have. The names are also compile-time constants, because
 * the runner's picocli {@code @Command(name = ...)} annotations need constants.
 */
public enum BenchmarkType {
    JMH(BenchmarkType.JMH_NAME, BenchmarkType.JMH_WORKING_OUTPUT, "jmh-output.txt"),
    JMH_WITH_ASYNC(BenchmarkType.JMH_WITH_ASYNC_NAME, BenchmarkType.JMH_WORKING_OUTPUT, "jmh-with-async-output.txt"),
    JMH_WITH_PROF(BenchmarkType.JMH_WITH_PROF_NAME, BenchmarkType.JMH_WORKING_OUTPUT, "jmh-profiler-output.txt"),
    JCSTRESS(BenchmarkType.JCSTRESS_NAME, "jcstress-output.txt", "jcstress-output.txt");

    public static final String JMH_NAME = "jmh";
    public static final String JMH_WITH_ASYNC_NAME = "jmh-with-async";
    public static final String JMH_WITH_PROF_NAME = "jmh-with-prof";
    public static final String JCSTRESS_NAME = "jcstress";

    /** Every JMH type writes its process output to the same file; only the uploaded name differs. */
    private static final String JMH_WORKING_OUTPUT = "jmh-output.txt";

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
