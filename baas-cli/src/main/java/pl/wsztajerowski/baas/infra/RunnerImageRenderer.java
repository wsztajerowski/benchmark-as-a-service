package pl.wsztajerowski.baas.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns {@code infra/runner-image.yaml} into the two things the stack needs: the AWSTOE component
 * document that bakes the toolchain, and the recipe inputs (parent AMI, version) that pin it.
 *
 * <p>The component is rendered here rather than written into {@code cf-template-core.yaml} so that
 * the YAML file stays the single place a tool version is declared — the template holds resource
 * wiring and nothing a benchmark can observe.
 */
public class RunnerImageRenderer {

    private static final String DEFINITION_RESOURCE = "/templates/runner-image.yaml";

    /**
     * CloudFormation caps a parameter value at 4096 bytes, and the component travels as one.
     * Overflowing it fails at stack-update time with a message that names the parameter and
     * nothing else, so {@link #renderComponent} checks the size itself and says what to do.
     */
    static final int CFN_PARAMETER_LIMIT_BYTES = 4096;

    public static final String PARAM_IMAGE_VERSION = "RunnerImageVersion";
    public static final String PARAM_PARENT_AMI_ID = "RunnerParentAmiId";
    public static final String PARAM_COMPONENT_DATA = "RunnerImageComponentData";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final RunnerImageDefinition definition;

    public RunnerImageRenderer() {
        this(load(DEFINITION_RESOURCE));
    }

    RunnerImageRenderer(RunnerImageDefinition definition) {
        this.definition = definition;
    }

    public RunnerImageDefinition definition() {
        return definition;
    }

    /**
     * The AWSTOE component document. Its bytes are the image's identity as far as Image Builder is
     * concerned: a component version is immutable, so re-registering the same version with a
     * different document is rejected, which is what {@code baas admin build-image} preflights.
     */
    public String renderComponent() {
        var tools = definition.tools();
        var kernel = definition.kernel();
        var asyncProfiler = tools.asyncProfiler();

        String document = COMPONENT_TEMPLATE
            .replace("{{IMAGE_VERSION}}", definition.imageVersion())
            .replace("{{CORRETTO_NVR}}", tools.corretto().nvr())
            .replace("{{PERF_NVR}}", tools.perf().nvr())
            .replace("{{AWSCLI_NVR}}", tools.awsCli().nvr())
            .replace("{{ASYNC_PROFILER_URL}}", asyncProfiler.downloadUrl())
            .replace("{{ASYNC_PROFILER_HOME}}", asyncProfiler.installPath())
            .replace("{{ASYNC_PROFILER_LIB}}", asyncProfiler.libraryPath())
            .replace("{{PERF_EVENT_PARANOID}}", Integer.toString(kernel.perfEventParanoid()))
            .replace("{{KPTR_RESTRICT}}", Integer.toString(kernel.kptrRestrict()))
            .replace("{{TRANSPARENT_HUGEPAGES}}", kernel.transparentHugepages())
            .replace("{{SWAP_COMMANDS}}", kernel.swapDisabled() ? DISABLE_SWAP : KEEP_SWAP);

        int size = document.getBytes(StandardCharsets.UTF_8).length;
        if (size > CFN_PARAMETER_LIMIT_BYTES) {
            throw new IllegalStateException(
                "Rendered Image Builder component is %d bytes; CloudFormation caps a parameter value at %d. "
                    .formatted(size, CFN_PARAMETER_LIMIT_BYTES)
                    + "Shorten the build steps in RunnerImageRenderer, or split the component in two.");
        }
        return document;
    }

    /**
     * Stack parameters carrying the declaration into {@code cf-template-core.yaml}. Both
     * {@code baas admin setup} and {@code baas admin build-image} render these from the same
     * classpath resource, so the two commands always submit identical values.
     */
    public Map<String, String> stackParameters() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put(PARAM_IMAGE_VERSION, definition.imageVersion());
        parameters.put(PARAM_PARENT_AMI_ID, definition.parentImage().amiId());
        parameters.put(PARAM_COMPONENT_DATA, renderComponent());
        return parameters;
    }

    private static RunnerImageDefinition load(String classpathResource) {
        try (InputStream is = RunnerImageRenderer.class.getResourceAsStream(classpathResource)) {
            if (is == null) {
                throw new IllegalStateException(classpathResource + " is not on the classpath");
            }
            return YAML.readValue(is, RunnerImageDefinition.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * {@code set -euxo pipefail} is right here and wrong in {@code UserDataScriptBuilder}: a bake
     * that half-installs the toolchain must abort, whereas a runner that exits early orphans a
     * paid instance before its watchdog starts.
     */
    private static final String COMPONENT_TEMPLATE = """
        name: baas-runner-toolchain
        description: BaaS runner measurement environment {{IMAGE_VERSION}}
        schemaVersion: 1.0
        phases:
          - name: build
            steps:
              - name: InstallToolchain
                action: ExecuteBash
                inputs:
                  commands:
                    - |
                      set -euxo pipefail
                      dnf install -y '{{CORRETTO_NVR}}'
                      dnf install -y '{{PERF_NVR}}'
                      dnf install -y --allowerasing '{{AWSCLI_NVR}}' || dnf downgrade -y '{{AWSCLI_NVR}}'
                      curl -fsSL '{{ASYNC_PROFILER_URL}}' -o /tmp/ap.tgz
                      rm -rf '{{ASYNC_PROFILER_HOME}}'
                      mkdir -p /app
                      tar -xf /tmp/ap.tgz -C /tmp
                      mv /tmp/async-profiler-*-linux-x64 '{{ASYNC_PROFILER_HOME}}'
                      rm -f /tmp/ap.tgz
              - name: ApplyKernelTunables
                action: ExecuteBash
                inputs:
                  commands:
                    - |
                      set -euxo pipefail
                      cat > /etc/sysctl.d/99-baas-benchmark.conf <<'SYSCTL'
                      kernel.perf_event_paranoid = {{PERF_EVENT_PARANOID}}
                      kernel.kptr_restrict = {{KPTR_RESTRICT}}
                      SYSCTL
                      grubby --update-kernel=ALL --args='transparent_hugepage={{TRANSPARENT_HUGEPAGES}}'
                      {{SWAP_COMMANDS}}
                      printf '%s\\n' '{{IMAGE_VERSION}}' > /etc/baas-image-version
              - name: VerifyToolchain
                action: ExecuteBash
                inputs:
                  commands:
                    - |
                      set -euxo pipefail
                      java -version
                      perf --version
                      aws --version
                      test -f '{{ASYNC_PROFILER_LIB}}'
        """;

    /**
     * Swapping mid-measurement is an outlier indistinguishable from a real regression.
     *
     * <p>One line, not two: the substitution lands inside a YAML block scalar, and a second line
     * would have to carry the block's exact indentation as a literal — a coupling that survives
     * neither a reformat nor a reader.
     */
    private static final String DISABLE_SWAP =
        "swapoff -a || true; sed -i '/[[:space:]]swap[[:space:]]/d' /etc/fstab";

    private static final String KEEP_SWAP = "true # swap left as the parent image configured it";
}
