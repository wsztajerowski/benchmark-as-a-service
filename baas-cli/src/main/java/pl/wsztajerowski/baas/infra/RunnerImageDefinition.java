package pl.wsztajerowski.baas.infra;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Parsed {@code infra/runner-image.yaml} — the declaration of the measurement environment.
 *
 * <p>Deliberately not the same shape as {@code <result-path>/environment.json}: this is what was
 * asked for, that is what was got. The observation is strictly richer (instance type, CPU model,
 * resolved patch levels), and it is the one that answers whether two results are comparable.
 *
 * <p>Unknown fields are ignored so a field added to the YAML for a newer CLI does not break an
 * older one reading the same repository checkout.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunnerImageDefinition(
    String imageVersion,
    ParentImage parentImage,
    Tools tools,
    Kernel kernel
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ParentImage(String region, String amiId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tools(Package corretto, Package perf, Package awsCli, AsyncProfiler asyncProfiler) {
    }

    /** An RPM pinned to an exact {@code name-version-release}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
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

    @JsonIgnoreProperties(ignoreUnknown = true)
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Kernel(
        int perfEventParanoid,
        int kptrRestrict,
        String transparentHugepages,
        String swap
    ) {
        public boolean swapDisabled() {
            return "disabled".equalsIgnoreCase(swap);
        }
    }
}
