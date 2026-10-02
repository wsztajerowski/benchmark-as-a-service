package pl.wsztajerowski.baas.infra;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Parsed {@code infra/runner-image.yaml} — the declaration of the measurement environment.
 *
 * <p>Deliberately not the same shape as {@code <result-path>/environment.json}: this is what was
 * asked for, that is what was got. The observation is strictly richer (instance type, CPU model,
 * resolved patch levels), and it is the one that answers whether two results are comparable.
 *
 * <p>Strict, unlike {@code ~/.baas/config.yaml}: an unknown key fails the load, and so does a
 * missing value. Each CLI reads only the copy bundled in its own JAR, so no older file carries
 * retired keys — and leniency here turned a typo ({@code perf_event_paranoid}) into a silently
 * permissive image: the field defaulted to {@code 0}, below the declared {@code 1}, and the bake,
 * the component and every run's manifest reported it as though it had been chosen.
 */
public record RunnerImageDefinition(
    String imageVersion,
    ParentImage parentImage,
    Tools tools,
    Kernel kernel
) {

    /**
     * Every value the bake uses, by its path in the YAML, so the error names the line to fix.
     * {@code kernelRelease} is required on {@code perf} alone, the one package pinned to a kernel.
     */
    public RunnerImageDefinition requireComplete() {
        var missing = new java.util.ArrayList<String>();
        check(missing, "imageVersion", imageVersion);
        check(missing, "parentImage", parentImage);
        if (parentImage != null) {
            check(missing, "parentImage.region", parentImage.region());
            check(missing, "parentImage.amiId", parentImage.amiId());
        }
        check(missing, "tools", tools);
        if (tools != null) {
            checkPackage(missing, "tools.corretto", tools.corretto());
            checkPackage(missing, "tools.perf", tools.perf());
            if (tools.perf() != null) {
                check(missing, "tools.perf.kernelRelease", tools.perf().kernelRelease());
            }
            checkPackage(missing, "tools.awsCli", tools.awsCli());
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

        public record ParentImage(String region, String amiId) {
    }

        public record Tools(Package corretto, Package perf, Package awsCli, AsyncProfiler asyncProfiler) {
    }

    /** An RPM pinned to an exact {@code name-version-release}. */
        public record Package(
        @JsonProperty("package") String packageName,
        String version,
        /** Only {@code perf} carries this; it is informational and travels with the perf pin. */
        String kernelRelease
    ) {
        /** The {@code dnf install} argument: {@code name-version-release}. */
        public String nvr() {
            return packageName + "-" + version;
        }
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
