package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RunnerImageRendererTest {

    private final RunnerImageRenderer renderer = new RunnerImageRenderer();
    private final RunnerImageDefinition definition = renderer.definition();

    @Test
    void everyPinnedVersionReachesTheComponent() {
        String component = renderer.renderComponent();
        var tools = definition.tools();

        assertThat(component)
            .as("a version declared in runner-image.yaml but absent from the component is a lie "
                + "told to every result tagged with this image version")
            .contains(tools.corretto().nvr())
            .contains(tools.perf().nvr())
            .contains(tools.awsCli().nvr())
            .contains(tools.asyncProfiler().downloadUrl());
    }

    @Test
    void kernelTunablesAreAppliedByTheComponent() {
        String component = renderer.renderComponent();
        var kernel = definition.kernel();

        assertThat(component)
            .as("async-profiler cannot walk kernel stacks without these two")
            .contains("kernel.perf_event_paranoid = " + kernel.perfEventParanoid())
            .contains("kernel.kptr_restrict = " + kernel.kptrRestrict())
            .contains("transparent_hugepage=" + kernel.transparentHugepages());

        assertThat(component)
            .as("declaring swap disabled has to remove it from fstab, not just for this boot")
            .contains("swapoff -a")
            .contains("/etc/fstab");
    }

    @Test
    void asyncProfilerLandsWhereTheRunnerLooksForIt() {
        assertThat(definition.tools().asyncProfiler().libraryPath())
            .as("JmhWithAsyncProfilerSubcommand defaults --async-path to this exact path; a "
                + "mismatch fails only on jmh-with-async runs, long after the bake succeeded")
            .isEqualTo("/app/async-profiler/lib/libasyncProfiler.so");

        assertThat(renderer.renderComponent())
            .contains("test -f '/app/async-profiler/lib/libasyncProfiler.so'");
    }

    @Test
    void parentImageIsAnExactAmiId() {
        assertThat(definition.parentImage().amiId())
            .as("a 'latest' selector would re-base the measurement environment on whatever AWS "
                + "published that morning — the drift this change exists to remove")
            .matches("ami-[0-9a-f]{8,17}");

        assertThat(definition.parentImage().region())
            .as("an AMI ID means nothing outside the region it was registered in")
            .isEqualTo("eu-central-1");
    }

    @Test
    void componentIsValidYamlDeclaringABuildPhase() {
        Object parsed = new Yaml().load(renderer.renderComponent());

        assertThat(parsed).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        var document = (Map<String, Object>) parsed;
        assertThat(document).containsEntry("schemaVersion", 1.0);

        @SuppressWarnings("unchecked")
        var phases = (List<Map<String, Object>>) document.get("phases");
        assertThat(phases).extracting(phase -> phase.get("name")).containsExactly("build");
    }

    @Test
    void componentFitsInACloudFormationParameter() {
        int size = renderer.renderComponent().getBytes(StandardCharsets.UTF_8).length;

        assertThat(size)
            .as("the component travels to the stack as a parameter value; overflowing the 4096-byte "
                + "cap fails at stack-update time with a message that names nothing useful")
            .isLessThanOrEqualTo(RunnerImageRenderer.CFN_PARAMETER_LIMIT_BYTES);
    }

    @Test
    void stackParametersCarryTheDeclarationIntoTheTemplate() {
        assertThat(renderer.stackParameters())
            .containsEntry(RunnerImageRenderer.PARAM_IMAGE_VERSION, definition.imageVersion())
            .containsEntry(RunnerImageRenderer.PARAM_PARENT_AMI_ID, definition.parentImage().amiId())
            .containsEntry(RunnerImageRenderer.PARAM_COMPONENT_DATA, renderer.renderComponent());
    }

    /**
     * Image Builder versions the Component and the ImageRecipe together off this one field, and it
     * is recorded on every result as the {@code imageVersion} tag. A non-semver value is rejected
     * only once a build has already been started.
     */
    @Test
    void imageVersionIsSemver() {
        assertThat(definition.imageVersion()).matches("\\d+\\.\\d+\\.\\d+");
    }

    @Test
    void perfIsPinnedToTheParentImageKernel() {
        var perf = definition.tools().perf();

        assertThat(perf.version())
            .as("the perf RPM is built from one kernel build; a mismatch against the parent AMI's "
                + "kernel breaks profiling in a way no unit test on this side can see")
            .isEqualTo(perf.kernelRelease());
    }
}
