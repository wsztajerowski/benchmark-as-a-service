package pl.wsztajerowski.commands;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import pl.wsztajerowski.services.options.JCStressOptions;

import java.nio.file.Path;

@Command
public class ApiJCStressOptions {

    @Option(names = {"-c", "--number-of-cpu"}, description = "Number of CPUs to use. Defaults to all CPUs in the system. Reducing the number of CPUs limits the amount of resources (including memory) the run is using.")
    Integer cpuNumber;
    @Option(names = {"-f", "--forks-per-test"}, description = "Should fork each test N times. \"0\" to run in the embedded mode with occasional forking.")
    Integer forks;
    @Option(names = {"-fsm", "--fork-multiplier"}, description = "Fork multiplier for randomized/stress tests. This allows more efficient randomized testing, as each fork would use a different seed.")
    Integer forkMultiplier;
    @Option(names = {"-hs", "--heap-size"}, description = "Java heap size per fork, in megabytes. This affects the stride size: maximum footprint will never be exceeded, regardless of min/max stride sizes.")
    Integer heapSize;
    @Option(names = "-jvmArgs", description = "Use given JVM arguments. This disables JVM flags auto-detection, and runs only the single JVM mode. Either a single space-separated option line, or multiple options are accepted. This option only affects forked runs.")
    String jvmArgs;
    @Option(names = "-jvmArgsPrepend", description = "Prepend given JVM arguments to auto-detected configurations. Either a single space-separated option line, or multiple options are accepted. This option only affects forked runs.")
    String jvmArgsPrepend;
    @Option(names = "-spinStyle", description = "Busy loop wait style. HARD = hard busy loop; THREAD_YIELD = use Thread.yield(); THREAD_SPIN_WAIT = use Thread.onSpinWait(); LOCKSUPPORT_PARK_NANOS = use LockSupport.parkNanos().")
    String spinStyle;
    @Option(names = {"-r", "--report-path"}, defaultValue = "jcstress-results", description = "Target destination to put the report into.")
    Path reportPath;
    @Option(names = {"-sc", "--split-compilation-modes"}, negatable = true, description = "Use split per-actor compilation mode, if available.")
    Boolean splitCompilationModes;
    @Option(names = {"-pth", "--pre-touch-heap"}, negatable = true, description = "Pre-touch Java heap, if possible.")
    Boolean preTouchHeap;
    @Option(names = "-strideCount", description = "Internal stride count per epoch. Larger value increases cache footprint.")
    Integer strideCount;
    @Option(names = "-strideSize", description = "Internal stride size. Larger value decreases the synchronization overhead, but also reduces the number of collisions.")
    Integer strideSize;
    @Option(names = {"--process-output"}, description = "Write tests process output to a given file. (default: ${DEFAULT-VALUE})")
    Path processOutput = TestWrapper.getWorkingDirectory().resolve("jcstress-output.txt");
    @CommandLine.Parameters(index = "0", description = "Test name regex", arity = "0..1")
    String testNameRegex;

    public JCStressOptions getValues(){
        return new JCStressOptions(cpuNumber,
            forks,
            forkMultiplier,
            heapSize,
            jvmArgs,
            jvmArgsPrepend,
            spinStyle,
            reportPath,
            splitCompilationModes,
            preTouchHeap,
            strideCount,
            strideSize,
            testNameRegex,
            processOutput);
    }

}
