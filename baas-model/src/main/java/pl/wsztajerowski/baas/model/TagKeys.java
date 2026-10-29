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
    public static final String BRANCH = "branch";
    public static final String JDK = "jdk";
    /** {@code java.vendor} on the instance: tells apart two vendors' builds of one Java version. */
    public static final String JVM_VENDOR = "jvmVendor";
    public static final String CPU_MODEL = "cpuModel";
    public static final String CPU_ARCH = "cpuArch";
    public static final String INSTANCE_TYPE = "instanceType";
    public static final String IMAGE_VERSION = "imageVersion";
    public static final String SOURCE = "source";

    public static final Set<String> KNOWN = Set.of(
        PROJECT, TYPE, COMMIT, BRANCH, SOURCE, JDK, JVM_VENDOR, CPU_MODEL, CPU_ARCH, INSTANCE_TYPE,
        IMAGE_VERSION);

    /**
     * The convention a CI job uses to keep its measurements out of everyone's results: a row tagged
     * {@code hidden=true} is filtered out server-side unless a query asks for it. Not in
     * {@link #KNOWN} — it is a convention a caller sets, not a dimension anyone slices by.
     */
    public static final String HIDDEN = "hidden";

    /** {@link #SOURCE} values baas run derives; a caller may supply any other. */
    public static final String SOURCE_CI = "ci";
    public static final String SOURCE_LOCAL = "local";

    /**
     * Observed on the instance (or derived from the benchmark type), so a caller may not set them:
     * an override would let a result's tags disagree with its own environment.json. `project`,
     * `commit`, `branch` and `source` are deliberately absent — design.md specifies caller-wins for
     * those. `source` in particular says how a job was triggered, which the instance never
     * observes: a forged value misleads nobody about the environment, and reserving it would
     * foreclose a consumer labelling a nightly or release job.
     */
    public static final List<String> MACHINE_OBSERVED =
        List.of(IMAGE_VERSION, INSTANCE_TYPE, JDK, JVM_VENDOR, CPU_MODEL, CPU_ARCH, TYPE);

    private TagKeys() {}

    /**
     * A {@code key=value} tag pair, both sides non-empty; anything else is a usage error. Every
     * command that takes a tag filter parses it here, so they reject the same inputs.
     */
    public static String[] parsePair(String text) {
        int eq = text == null ? -1 : text.indexOf('=');
        if (eq <= 0 || eq == text.length() - 1) {
            throw new IllegalArgumentException("Expected key=value, got '" + text + "'.");
        }
        return new String[]{text.substring(0, eq), text.substring(eq + 1)};
    }
}
