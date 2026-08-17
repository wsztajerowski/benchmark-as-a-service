package pl.wsztajerowski.baas.model;

import java.util.List;
import java.util.Set;

/**
 * The known-key tag vocabulary, defined once for both the runner and the CLI. Keys outside this
 * set are permitted — a query naming one warns rather than silently returning nothing.
 */
public final class TagKeys {

    public static final String PROJECT = "project";
    public static final String TYPE = "type";
    public static final String COMMIT = "commit";
    public static final String JDK = "jdk";
    public static final String CPU_MODEL = "cpuModel";
    public static final String CPU_ARCH = "cpuArch";
    public static final String INSTANCE_TYPE = "instanceType";
    public static final String IMAGE_VERSION = "imageVersion";

    public static final Set<String> KNOWN =
        Set.of(PROJECT, TYPE, COMMIT, JDK, CPU_MODEL, CPU_ARCH, INSTANCE_TYPE, IMAGE_VERSION);

    /**
     * Observed on the instance (or derived from the benchmark type), so a caller may not set them:
     * an override would let a result's tags disagree with its own environment.json. `project` and
     * `commit` are deliberately absent — design.md specifies caller-wins for those.
     */
    public static final List<String> MACHINE_OBSERVED =
        List.of(IMAGE_VERSION, INSTANCE_TYPE, JDK, CPU_MODEL, CPU_ARCH, TYPE);

    private TagKeys() {}
}
