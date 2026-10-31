package pl.wsztajerowski.baas.image;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
            .as("JmhSubcommand defaults --async-path to this exact path; a "
                + "mismatch fails only on -prof async jobs, long after the bake succeeded")
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
    private static final String BUCKET = "baas-123456789012";
    private static final String REGION = "eu-central-1";

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
        String contract = renderer.renderContract(LABEL, BUCKET, REGION);

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
        String contract = renderer.renderContract(LABEL, BUCKET, REGION);

        assertThat(contract)
            .contains("command -v java")
            .contains("-ge " + renderer.javaFloor())
            .contains("java.specification.version")
            .contains("aws --version")
            .contains("systemctl is-enabled amazon-ssm-agent")
            .contains("test -f '/app/async-profiler/lib/libasyncProfiler.so'")
            .contains("test -x '/app/async-profiler/bin/asprof'")
            .contains("rpm -q \"perf-${kernel}\"")
            .contains("sysctl -n kernel.perf_event_paranoid)\" -le 1")
            .contains("sysctl -n kernel.kptr_restrict)\" -eq 0");
    }

    @Test
    void theContractLeavesMeasurementChoicesToTheExtension() {
        assertThat(renderer.renderContract(LABEL, BUCKET, REGION))
            .as("JVM vendor, THP and swap are the extension's to change; each job records them")
            .doesNotContain("transparent_hugepage")
            .doesNotContain("swap")
            .doesNotContain("amazon-corretto")
            .doesNotContain("Amazon.com");
    }

    // ─── JDKs: /opt/jdks, baas-install-jdk and the contract's list ──────────────

    @Test
    void theBaseLinksItsCorrettoAsTheDefaultJdkAndInstallsTheHelper() {
        assertThat(renderer.renderBase())
            .contains("ln -s \"$(dirname \"$(dirname \"$(readlink -f \"$(command -v java)\")\")\")\" '/opt/jdks/corretto-25'")
            .contains("cat > /usr/local/bin/baas-install-jdk <<'HELPER'")
            .contains("chmod 755 /usr/local/bin/baas-install-jdk");
        assertThat(definition.tools().corretto().packageName())
            .as("-headless has no jcmd, which JMH's -prof jfr runs from the benchmark JVM's java.home")
            .isEqualTo("java-25-amazon-corretto-devel");
    }

    @Test
    void anExtensionAddsAJdkWithTheHelper(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path archive = jdkArchive(dir, "jdk-26.0.2", true);

        var result = installJdk(dir, "temurin-26", archive.toUri().toString(), sha256(archive));

        assertThat(result.exit()).as(result.output()).isZero();
        assertThat(dir.resolve("jdks/temurin-26/bin/java")).isExecutable();
        assertThat(dir.resolve("jdks/temurin-26/bin/jcmd")).isExecutable();
        assertThat(result.output()).contains("fake 26.0.2");
        assertThat(leftovers(dir)).isEmpty();
    }

    /** Nothing is unpacked from an archive whose digest is not the one the extension wrote. */
    @Test
    void theHelperRefusesAWrongDigest(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path archive = jdkArchive(dir, "jdk-26.0.2", true);

        var result = installJdk(dir, "temurin-26", archive.toUri().toString(), "0".repeat(64));

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.output()).contains("does not have SHA-256");
        assertThat(dir.resolve("jdks/temurin-26")).doesNotExist();
        assertThat(leftovers(dir)).isEmpty();
    }

    @Test
    void theHelperRefusesAnIdAlreadyPresent(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path archive = jdkArchive(dir, "jdk-26.0.2", true);
        Files.createDirectories(dir.resolve("jdks/corretto-25"));

        var result = installJdk(dir, "corretto-25", archive.toUri().toString(), sha256(archive));

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.output()).contains("/corretto-25 already exists");
        assertThat(dir.resolve("jdks/corretto-25")).isEmptyDirectory();
    }

    /** A JRE runs a benchmark, but -prof jfr then fails every iteration: refused at the bake. */
    @Test
    void theHelperRefusesAJreWithoutJcmd(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path archive = jdkArchive(dir, "jre-26.0.2", false);

        var result = installJdk(dir, "temurin-26-jre", archive.toUri().toString(), sha256(archive));

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.output()).contains("no bin/jcmd");
        assertThat(dir.resolve("jdks/temurin-26-jre")).doesNotExist();
        assertThat(leftovers(dir)).isEmpty();
    }

    @Test
    void theHelperRefusesAnIdOutsideTheFormat(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path archive = jdkArchive(dir, "jdk-26.0.2", true);

        var result = installJdk(dir, "Temurin_26", archive.toUri().toString(), sha256(archive));

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.output()).contains("id 'Temurin_26' must match");
    }

    @Test
    void theContractRecordsTheImagesJdksWhereThePublishReadsThem() {
        assertThat(renderer.renderContract(LABEL, BUCKET, REGION))
            .contains("aws s3 cp /tmp/jdks.json 's3://" + BUCKET + "/image-builds/" + LABEL + "/jdks.json' --region '"
                + REGION + "'");
        assertThat(RunnerImageRenderer.jdkListKey(LABEL)).isEqualTo("image-builds/" + LABEL + "/jdks.json");
    }

    /** The list names every selectable JDK as the booted image has it, the default first. */
    @Test
    void theContractListsTheDefaultFirstThenTheOthers(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        fakeJdk(dir, "zulu-21", "21.0.5", "Azul Systems, Inc.", true);
        fakeJdk(dir, "corretto-25", "25.0.4", "Amazon.com Inc.", true);
        fakeJdk(dir, "temurin-26", "26.0.2", "Eclipse \"Adoptium\"", true);

        var result = contractJdks(dir);

        assertThat(result.output()).isEqualTo("failed=0\n[{\"id\":\"corretto-25\",\"release\":\"25.0.4\","
            + "\"vendor\":\"Amazon.com Inc.\",\"default\":true},{\"id\":\"temurin-26\",\"release\":\"26.0.2\","
            + "\"vendor\":\"Eclipse \\\"Adoptium\\\"\",\"default\":false},{\"id\":\"zulu-21\",\"release\":\"21.0.5\","
            + "\"vendor\":\"Azul Systems, Inc.\",\"default\":false}]\nids=corretto-25,temurin-26,zulu-21");
        var parsed = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.output().lines().toList().get(1));
        assertThat(parsed.get(1).get("vendor").asText()).isEqualTo("Eclipse \"Adoptium\"");
    }

    @Test
    void theContractFailsAJdkWithoutJcmdAMalformedIdAndAMissingDefault(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        fakeJdk(dir, "some-jre", "26.0.2", "Eclipse Adoptium", false);
        Files.createDirectories(dir.resolve("jdks/Temurin_26"));

        var result = contractJdks(dir);

        assertThat(result.output())
            .contains("failed=1")
            .contains("no default JDK " + dir.resolve("jdks/corretto-25"))
            .contains("JDK some-jre has no bin/jcmd")
            .contains("JDK id 'Temurin_26' in " + dir.resolve("jdks") + " must match");
    }

    @Test
    void theContractFailsIdsTooLongForAnAmiTag(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        fakeJdk(dir, "corretto-25", "25.0.4", "Amazon.com Inc.", true);
        for (int i = 0; i < 9; i++) {
            fakeJdk(dir, "temurin-26-build-" + "x".repeat(10) + i, "26.0.2", "Eclipse Adoptium", true);
        }

        assertThat(contractJdks(dir).output()).contains("failed=1").contains("above the 256 an AMI tag holds");
    }

    private record Bash(int exit, String output) {
    }

    private static Bash bash(String program) throws Exception {
        var process = new ProcessBuilder("bash", "-c", program).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        return new Bash(process.waitFor(), output);
    }

    /** The rendered helper, pointed at {@code dir/jdks} instead of /opt/jdks, run with these arguments. */
    private Bash installJdk(Path dir, String id, String url, String sha256) throws Exception {
        String base = commands(renderer.renderBase()).stream().filter(c -> c.contains("<<'HELPER'")).findFirst().orElseThrow();
        String helper = base.substring(base.indexOf("<<'HELPER'\n") + 11, base.indexOf("\nHELPER\n"))
            .replace("root='/opt/jdks'", "root='" + dir.resolve("jdks") + "'")
            .replace("mktemp -d /opt/.baas-install-jdk.XXXXXX", "mktemp -d '" + dir + "/.baas-install-jdk.XXXXXX'");
        Files.createDirectories(dir.resolve("jdks"));
        Path script = Files.writeString(dir.resolve("baas-install-jdk"), helper);
        return bash("bash '" + script + "' '" + id + "' '" + url + "' '" + sha256 + "'");
    }

    /** The contract's JDK checks and list, run against {@code dir/jdks}; prints failed, the list and the ids. */
    private Bash contractJdks(Path dir) throws Exception {
        String contract = commands(renderer.renderContract(LABEL, BUCKET, REGION)).stream()
            .filter(c -> c.contains("root='/opt/jdks'")).findFirst().orElseThrow();
        String block = contract.substring(contract.indexOf("root='/opt/jdks'"), contract.indexOf("[ \"$failed\" -eq 0 ] || exit 1"))
            .replace("root='/opt/jdks'", "root='" + dir.resolve("jdks") + "'");
        Files.createDirectories(dir.resolve("jdks"));
        return bash("failed=0\nfail() { echo \"FAILED: $1\"; failed=1; }\n" + block
            + "echo \"failed=$failed\"\n[ \"$failed\" -eq 0 ] && printf '[%s]\\n' \"$json\"\necho \"ids=$ids\"\n");
    }

    /** A directory under {@code dir/jdks} whose bin/java answers -XshowSettings:properties like a JDK. */
    private static void fakeJdk(Path dir, String id, String release, String vendor, boolean jcmd) throws Exception {
        Path bin = Files.createDirectories(dir.resolve("jdks").resolve(id).resolve("bin"));
        executable(bin.resolve("java"), "#!/bin/sh\ncat >&2 <<'P'\nProperty settings:\n    java.vendor = " + vendor
            + "\n    java.vendor.url = https://example.com/\n    java.version = " + release + "\nP\n");
        if (jcmd) {
            executable(bin.resolve("jcmd"), "#!/bin/sh\n");
        }
    }

    /** A JDK archive as vendors ship one: a single top directory holding bin/. */
    private static Path jdkArchive(Path dir, String top, boolean jcmd) throws Exception {
        Path bin = Files.createDirectories(dir.resolve("src").resolve(top).resolve("bin"));
        executable(bin.resolve("java"), "#!/bin/sh\necho 'fake 26.0.2' >&2\n");
        if (jcmd) {
            executable(bin.resolve("jcmd"), "#!/bin/sh\n");
        }
        Path archive = dir.resolve(top + ".tar.gz");
        assertThat(bash("tar -czf '" + archive + "' -C '" + dir.resolve("src") + "' '" + top + "'").exit()).isZero();
        return archive;
    }

    private static void executable(Path file, String content) throws Exception {
        Files.writeString(file, content);
        Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private static String sha256(Path file) throws Exception {
        return java.util.HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static List<String> leftovers(Path dir) throws Exception {
        try (var entries = Files.list(dir)) {
            return entries.map(path -> path.getFileName().toString()).filter(name -> name.startsWith(".baas-install-jdk")).toList();
        }
    }

    @Test
    void theContractScriptsAreValidBash() throws Exception {
        for (String script : commands(renderer.renderContract(LABEL, BUCKET, REGION))) {
            Path file = Files.createTempFile("contract", ".sh");
            Files.writeString(file, script);
            var process = new ProcessBuilder("bash", "-n", file.toString()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor()).as(output).isZero();
        }
    }

    @Test
    void theContractFitsInACloudFormationParameter() {
        assertThat(renderer.renderContract(LABEL, BUCKET, REGION).getBytes(StandardCharsets.UTF_8).length)
            .isLessThanOrEqualTo(RunnerImageRenderer.CFN_PARAMETER_LIMIT_BYTES);
    }

    /**
     * The plan compares each rendered document with the stack's copy, which DescribeStacks returns
     * with non-ASCII characters replaced by '?': one would make every build look changed.
     */
    @Test
    void renderedComponentsAreAscii() {
        for (String document : List.of(renderer.renderBase(), renderer.renderContract(LABEL, BUCKET, REGION))) {
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

    /** Review X2: the one download no package signature covers is checked before it is unpacked. */
    @Test
    void theAsyncProfilerDownloadIsCheckedBeforeItIsUnpacked() {
        String base = renderer.renderBase();
        String check = "echo '" + definition.tools().asyncProfiler().sha256() + "  /tmp/ap.tgz' | sha256sum -c -";

        assertThat(definition.tools().asyncProfiler().sha256()).matches("[0-9a-f]{64}");
        assertThat(base).contains(check);
        assertThat(base.indexOf(check)).isLessThan(base.indexOf("tar -xf /tmp/ap.tgz"));
    }

    @Test
    void aDeclarationWithoutTheAsyncProfilerDigestIsRejected() throws Exception {
        String withoutLine = shippedYaml().lines()
            .filter(line -> !line.strip().startsWith("sha256:"))
            .collect(java.util.stream.Collectors.joining("\n"));

        assertThatThrownBy(() -> parse(withoutLine))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("missing tools.asyncProfiler.sha256");
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
