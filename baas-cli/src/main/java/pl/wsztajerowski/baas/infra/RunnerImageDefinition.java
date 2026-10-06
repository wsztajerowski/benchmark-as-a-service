package pl.wsztajerowski.baas.infra;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Parsed {@code infra/runner-image.yaml} — the declaration of the measurement environment's base.
 * A deployment's extension is not part of it: that is an operator-owned AWSTOE document held in
 * the stack, with no schema here.
 *
 * <p>Deliberately not the same shape as {@code <result-path>/environment.json}: this is what was
 * asked for, that is what was got. The observation is strictly richer (instance type, CPU model,
 * resolved patch levels), and it is the one that answers whether two results are comparable.
 *
 * <p>Strict, unlike {@code ~/.baas/config.yaml}: an unknown key fails the load, and so does a
 * missing value. Each CLI reads only the copy bundled in its own JAR, so no older file carries
 * retired keys — and leniency here turned a typo ({@code perf_event_paranoid}) into a silently
 * permissive image: the field defaulted to {@code 0}, below the declared {@code 1}, and the bake,
 * the component and every job's manifest reported it as though it had been chosen.
 */
public record RunnerImageDefinition(
    String imageVersion,
    ParentImage parentImage,
    Tools tools,
    Kernel kernel
) {

    /**
     * Every value the bake uses, by its path in the YAML, so the error names the line to fix.
     */
    public RunnerImageDefinition requireComplete() {
        var missing = new java.util.ArrayList<String>();
        check(missing, "imageVersion", imageVersion);
        check(missing, "parentImage", parentImage);
        if (parentImage != null) {
            check(missing, "parentImage.amiName", parentImage.amiName());
        }
        check(missing, "tools", tools);
        if (tools != null) {
            checkPackage(missing, "tools.corretto", tools.corretto());
            check(missing, "tools.perf", tools.perf());
            if (tools.perf() != null) {
                check(missing, "tools.perf.package", tools.perf().packageName());
            }
            check(missing, "tools.asyncProfiler", tools.asyncProfiler());
            if (tools.asyncProfiler() != null) {
                check(missing, "tools.asyncProfiler.version", tools.asyncProfiler().version());
                check(missing, "tools.asyncProfiler.installPath", tools.asyncProfiler().installPath());
            }
        }
        check(missing, "kernel", kernel);
        if (kernel != null) {
            check(missing, "kernel.perfEventParanoid", kernel.perfEventParanoid());
            check(missing, "kernel.kptrRestrict", kernel.kptrRestrict());
            check(missing, "kernel.transparentHugepages", kernel.transparentHugepages());
            check(missing, "kernel.swap", kernel.swap());
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("runner-image.yaml is missing " + String.join(", ", missing));
        }
        return this;
    }

    private static void checkPackage(java.util.List<String> missing, String path, Package pkg) {
        check(missing, path, pkg);
        if (pkg != null) {
            check(missing, path + ".package", pkg.packageName());
            check(missing, path + ".version", pkg.version());
        }
    }

    private static void check(java.util.List<String> missing, String path, Object value) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            missing.add(path);
        }
    }

    /**
     * One exact AL2023 release, by AMI name. An AMI ID would bind the definition to one region; the
     * name resolves to the same release — same kernel, same repository — in every region.
     */
    public record ParentImage(String amiName) {
    }

    public record Tools(Package corretto, KernelPackage perf, AsyncProfiler asyncProfiler) {
    }

    /** An RPM pinned to an exact {@code name-version-release}. */
    public record Package(@JsonProperty("package") String packageName, String version) {
        /** The {@code dnf install} argument: {@code name-version-release}. */
        public String nvr() {
            return packageName + "-" + version;
        }
    }

    /**
     * An RPM built from one kernel build, so the only right version is the running kernel's. Pinning
     * one could only disagree with the parent image; the base installs the running kernel's instead.
     */
    public record KernelPackage(@JsonProperty("package") String packageName) {
    }

    public record AsyncProfiler(String version, String installPath) {
        public String downloadUrl() {
            return "https://github.com/async-profiler/async-profiler/releases/download/v%s/async-profiler-%s-linux-x64.tar.gz"
                .formatted(version, version);
        }

        /** What {@code JmhWithAsyncProfilerSubcommand}'s {@code --async-path} default resolves to. */
        public String libraryPath() {
            return installPath + "/lib/libasyncProfiler.so";
        }
    }

    public record Kernel(
        Integer perfEventParanoid,
        Integer kptrRestrict,
        String transparentHugepages,
        String swap
    ) {
        public boolean swapDisabled() {
            return "disabled".equalsIgnoreCase(swap);
        }
    }
}
