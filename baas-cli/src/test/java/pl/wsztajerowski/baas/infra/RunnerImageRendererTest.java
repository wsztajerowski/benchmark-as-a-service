package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunnerImageRendererTest {

    private final RunnerImageRenderer renderer = new RunnerImageRenderer();
    private final RunnerImageDefinition definition = renderer.definition();

    // ─── The base ────────────────────────────────────────────────────────────────

    @Test
    void everyPinnedVersionReachesTheBase() {
        String base = renderer.renderBase();
        var tools = definition.tools();

        assertThat(base)
            .as("a version declared in runner-image.yaml but absent from the base is a lie told to "
                + "every result tagged with this image version")
            .contains(tools.corretto().nvr())
            .contains(tools.asyncProfiler().downloadUrl());
    }

    @Test
    void perfIsInstalledForTheRunningKernelNotPinned() {
        assertThat(renderer.renderBase())
            .as("the perf RPM is built from one kernel build; the build instance runs the parent's "
                + "kernel, so uname -r names the only right one for any parent")
            .contains("dnf install -y \"perf-$(uname -r | sed 's/\\.x86_64$//')\"");
    }

    @Test
    void theBaseLeavesTheAwsCliToTheParent() {
        assertThat(renderer.renderBase())
            .as("the parent ships the AWS CLI; the contract checks it runs")
            .doesNotContain("awscli");
    }

    @Test
    void theBaseCarriesNothingThatDependsOnTheExtension() {
        assertThat(renderer.renderBase())
            .as("the label depends on the extension; if the base wrote it, every extension edit would "
                + "change the base and demand a hand-bumped version")
            .doesNotContain("/etc/baas-image-version");
    }

    @Test
    void kernelTunablesAreAppliedByTheBase() {
        String base = renderer.renderBase();
        var kernel = definition.kernel();

        assertThat(base)
            .as("async-profiler cannot walk kernel stacks without these two")
            .contains("kernel.perf_event_paranoid = " + kernel.perfEventParanoid())
            .contains("kernel.kptr_restrict = " + kernel.kptrRestrict())
            .contains("transparent_hugepage=" + kernel.transparentHugepages());

        assertThat(base)
            .as("declaring swap disabled has to remove it from fstab, not just for this boot")
            .contains("swapoff -a")
            .contains("/etc/fstab");
    }

    @Test
    void theBaseIsValidYamlDeclaringABuildPhaseOnly() {
        assertThat(phaseNames(renderer.renderBase())).containsExactly("build");
    }

    @Test
    void theBaseFitsInACloudFormationParameter() {
        assertThat(renderer.renderBase().getBytes(StandardCharsets.UTF_8).length)
            .as("the component travels to the stack as a parameter value; overflowing the 4096-byte "
                + "cap fails at stack-update time with a message that names nothing useful")
            .isLessThanOrEqualTo(RunnerImageRenderer.CFN_PARAMETER_LIMIT_BYTES);
    }

    // ─── The definition ──────────────────────────────────────────────────────────

    @Test
    void asyncProfilerLandsWhereTheRunnerLooksForIt() {
        assertThat(definition.tools().asyncProfiler().libraryPath())
            .as("JmhWithAsyncProfilerSubcommand defaults --async-path to this exact path; a "
                + "mismatch fails only on jmh-with-async jobs, long after the bake succeeded")
            .isEqualTo("/app/async-profiler/lib/libasyncProfiler.so");
    }

    @Test
    void parentImageIsOneExactReleaseNotASelector() {
        assertThat(definition.parentImage().amiName())
            .as("a selector would re-base the measurement environment on whatever AWS published that "
                + "morning — the drift the pinned parent exists to remove")
            .matches("al2023-ami-\\d{4}\\.\\d+\\.\\d{8}\\.\\d+-kernel-[\\d.]+-x86_64");
    }

    /** Image Builder accepts only numeric x.y.z, and a bad value is rejected only once a build started. */
    @Test
    void imageVersionIsSemver() {
        assertThat(definition.imageVersion()).matches("\\d+\\.\\d+\\.\\d+");
    }

    // ─── The contract ────────────────────────────────────────────────────────────

    private static final String LABEL = "1.3.0+ext.3f9a1c2e";

    @Test
    void theJavaFloorIsTheRunnersCompileTarget() throws Exception {
        String pom = Files.readString(Path.of("..", "pom.xml"));
        var target = Pattern.compile("<maven.compiler.target>(\\d+)</maven.compiler.target>").matcher(pom);
        assertThat(target.find()).isTrue();

        assertThat(renderer.javaFloor())
            .as("the runner JAR loads only on a JVM at least as new as its compile target")
            .isEqualTo(Integer.parseInt(target.group(1)));
    }

    @Test
    void theContractWritesTheLabelAndChecksItOnTheBootedImage() {
        String contract = renderer.renderContract(LABEL);

        assertThat(phaseNames(contract))
            .as("the test phase runs on an instance booted from the new image — the only place the "
                + "applied sysctls, the THP argument and an upgraded kernel are visible")
            .containsExactly("build", "test");
        assertThat(contract)
            .contains("printf '%s\\n' '" + LABEL + "' > /etc/baas-image-version")
            .contains("= '" + LABEL + "' ] || fail");
    }

    @Test
    void theContractChecksEverythingABaasWorkflowDependsOn() {
        String contract = renderer.renderContract(LABEL);

        assertThat(contract)
            .contains("command -v java")
            .contains("-ge " + renderer.javaFloor())
            .contains("java.specification.version")
            .contains("aws --version")
            .contains("test -f '/app/async-profiler/lib/libasyncProfiler.so'")
            .contains("test -x '/app/async-profiler/bin/asprof'")
            .contains("rpm -q \"perf-${kernel}\"")
            .contains("sysctl -n kernel.perf_event_paranoid)\" -le 1")
            .contains("sysctl -n kernel.kptr_restrict)\" -eq 0");
    }

    @Test
    void theContractLeavesMeasurementChoicesToTheExtension() {
        assertThat(renderer.renderContract(LABEL))
            .as("JVM vendor, THP and swap are the extension's to change; each job records them")
            .doesNotContain("transparent_hugepage")
            .doesNotContain("swap")
            .doesNotContain("corretto");
    }

    @Test
    void theContractScriptsAreValidBash() throws Exception {
        for (String script : commands(renderer.renderContract(LABEL))) {
            Path file = Files.createTempFile("contract", ".sh");
            Files.writeString(file, script);
            var process = new ProcessBuilder("bash", "-n", file.toString()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor()).as(output).isZero();
        }
    }

    @Test
    void theContractFitsInACloudFormationParameter() {
        assertThat(renderer.renderContract(LABEL).getBytes(StandardCharsets.UTF_8).length)
            .isLessThanOrEqualTo(RunnerImageRenderer.CFN_PARAMETER_LIMIT_BYTES);
    }

    /**
     * The plan compares each rendered document with the stack's copy, which DescribeStacks returns
     * with non-ASCII characters replaced by '?': one would make every build look changed.
     */
    @Test
    void renderedComponentsAreAscii() {
        for (String document : List.of(renderer.renderBase(), renderer.renderContract(LABEL))) {
            assertThat(document.chars().allMatch(c -> c == '\n' || (c >= 0x20 && c <= 0x7E))).isTrue();
        }
    }

    // ─── Review P12: the declaration is parsed strictly ──────────────────────────
    //
    // A typo used to be dropped silently and the field defaulted — perfEventParanoid to 0, more
    // permissive than the declared 1 — and the bake succeeded with it.

    private static String shippedYaml() throws Exception {
        try (var is = RunnerImageRenderer.class.getResourceAsStream("/templates/runner-image.yaml")) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static RunnerImageDefinition parse(String yaml) throws Exception {
        return RunnerImageRenderer.parse(new java.io.ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theShippedDeclarationParsesStrictly() throws Exception {
        assertThat(parse(shippedYaml()).kernel().perfEventParanoid()).isEqualTo(1);
    }

    @Test
    void aMistypedKeyIsRejectedByItsPath() throws Exception {
        String typo = shippedYaml().replace("perfEventParanoid:", "perf_event_paranoid:");

        assertThatThrownBy(() -> parse(typo))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unknown key 'kernel.perf_event_paranoid'")
            .hasMessageContaining("perfEventParanoid");
    }

    @Test
    void aRegionBoundParentIsRejected() throws Exception {
        String old = shippedYaml().replaceFirst("amiName: .*", "amiId: ami-070cc8ab883065d64");

        assertThatThrownBy(() -> parse(old))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unknown key 'parentImage.amiId'");
    }

    @Test
    void aMissingValueIsRejectedByItsPath() throws Exception {
        String withoutLine = shippedYaml().lines()
            .filter(line -> !line.strip().startsWith("kptrRestrict:"))
            .collect(java.util.stream.Collectors.joining("\n"));

        assertThatThrownBy(() -> parse(withoutLine))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("missing kernel.kptrRestrict");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> phases(String component) {
        var document = (Map<String, Object>) new Yaml().load(component);
        assertThat(document).containsEntry("schemaVersion", 1.0);
        return (List<Map<String, Object>>) document.get("phases");
    }

    private static List<Object> phaseNames(String component) {
        return phases(component).stream().map(phase -> phase.get("name")).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> commands(String component) {
        return phases(component).stream()
            .flatMap(phase -> ((List<Map<String, Object>>) phase.get("steps")).stream())
            .flatMap(step -> ((List<String>) ((Map<String, Object>) step.get("inputs")).get("commands")).stream())
            .toList();
    }
}
