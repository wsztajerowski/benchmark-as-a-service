package pl.wsztajerowski.baas.model;

import java.util.Arrays;
import java.util.List;

/**
 * The benchmark types: what {@code baas run <type>} accepts, the {@code benchmark-runner}
 * subcommand that executes it, and the name of the file its process output is written to and
 * uploaded as. One list, so the CLI cannot accept a type the runner does not have. The names are also
 * compile-time constants, because the runner's picocli {@code @Command(name = ...)} annotations need
 * constants.
 *
 * <p>One type per library: profiling is a JMH argument ({@code -prof}), not a type. The profiled
 * types {@code jmh-with-async} and {@code jmh-with-prof} were retired by
 * {@code benchmark-args-pass-through}.
 *
 * <p>The type is also what a stored measurement records as its {@code kind}: the constant's
 * {@link #name()} is written to the results table, so the constant names are a storage format and
 * must not be renamed.
 */
public enum BenchmarkType {
    JMH(BenchmarkType.JMH_NAME, "jmh-output.txt"),
    JCSTRESS(BenchmarkType.JCSTRESS_NAME, "jcstress-output.txt");

    public static final String JMH_NAME = "jmh";
    public static final String JCSTRESS_NAME = "jcstress";

    private final String commandName;
    private final String processOutputName;

    BenchmarkType(String commandName, String processOutputName) {
        this.commandName = commandName;
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
     * The benchmark process's output file: the name it is written to in the runner's working
     * directory as it runs, and the name it is uploaded as inside the job's prefix. One name for
     * both, so the instance's stop hook, which ships the file when a job is stopped early, leaves it
     * at the key a completed job's runner does.
     */
    public String processOutputName() {
        return processOutputName;
    }

    /** Every command name, in declaration order. */
    public static List<String> commandNames() {
        return Arrays.stream(values()).map(BenchmarkType::commandName).toList();
    }
}
