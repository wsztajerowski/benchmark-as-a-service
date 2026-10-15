package pl.wsztajerowski.baas.model;

import java.util.Arrays;
import java.util.List;

/**
 * The benchmark types: what {@code baas run <type>} accepts, the {@code benchmark-runner}
 * subcommand that executes it, and the file its process output is uploaded as. One list, so the CLI
 * cannot accept a type the runner does not have. The names are also compile-time constants, because
 * the runner's picocli {@code @Command(name = ...)} annotations need constants.
 */
public enum BenchmarkType {
    JMH(BenchmarkType.JMH_NAME, "jmh-output.txt"),
    JMH_WITH_ASYNC(BenchmarkType.JMH_WITH_ASYNC_NAME, "jmh-with-async-output.txt"),
    JMH_WITH_PROF(BenchmarkType.JMH_WITH_PROF_NAME, "jmh-profiler-output.txt"),
    JCSTRESS(BenchmarkType.JCSTRESS_NAME, "jcstress-output.txt");

    public static final String JMH_NAME = "jmh";
    public static final String JMH_WITH_ASYNC_NAME = "jmh-with-async";
    public static final String JMH_WITH_PROF_NAME = "jmh-with-prof";
    public static final String JCSTRESS_NAME = "jcstress";

    private final String commandName;
    private final String processOutputName;

    BenchmarkType(String commandName, String processOutputName) {
        this.commandName = commandName;
        this.processOutputName = processOutputName;
    }

    /** The {@code baas run} argument and the runner subcommand. */
    public String commandName() {
        return commandName;
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
