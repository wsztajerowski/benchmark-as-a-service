package pl.symentis.services.options;

import java.nio.file.Path;

public record JCStressOptions(
    Integer cpuNumber,
    Integer forks,
    Integer forkMultiplier,
    Integer heapSize,
    String jvmArgs,
    String jvmArgsPrepend,
    String spinStyle,
    Path reportPath,
    Boolean splitCompilationModes,
    Boolean preTouchHeap,
    Integer strideCount,
    Integer strideSize,
    String testNameRegex,
    Path processOutput) {
}
