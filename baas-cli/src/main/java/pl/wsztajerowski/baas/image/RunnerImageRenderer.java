package pl.wsztajerowski.baas.image;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import pl.wsztajerowski.baas.model.Jdks;

/**
 * Turns {@code infra/runner-image.yaml} into the two BaaS-owned AWSTOE components: the base, which
 * bakes the declared toolchain, and the contract, which runs last and fails the bake when anything
 * a BaaS workflow depends on is missing. The operator's extension sits between them and is never
 * rendered — it is stored as written.
 *
 * <p>The components are rendered here rather than written into {@code cf-template-core.yaml} so
 * that the YAML file stays the single place a base tool version is declared — the template holds
 * resource wiring and nothing a benchmark can observe.
 */
public class RunnerImageRenderer {

    private static final String DEFINITION_RESOURCE = "/templates/runner-image.yaml";

    /**
     * Written at build time from the root POM's {@code maven.compiler.target}: the Java version the
     * runner JAR is compiled for, and so the lowest one the contract accepts on {@code PATH}.
     */
    private static final String BUILD_PROPERTIES_RESOURCE = "/baas-build.properties";

    /**
     * CloudFormation caps a parameter value at 4096 bytes, and every component travels as one.
     * Overflowing it fails at stack-update time with a message that names the parameter and
     * nothing else, so each render checks the size itself and says what to do.
     */
    static final int CFN_PARAMETER_LIMIT_BYTES = 4096;

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final RunnerImageDefinition definition;
    private final int javaFloor;

    public RunnerImageRenderer() {
        this(load(DEFINITION_RESOURCE), loadJavaFloor());
    }

    RunnerImageRenderer(RunnerImageDefinition definition, int javaFloor) {
        this.definition = definition;
        this.javaFloor = javaFloor;
    }

    public RunnerImageDefinition definition() {
        return definition;
    }

    /** The lowest {@code java.specification.version} the contract accepts. */
    int javaFloor() {
        return javaFloor;
    }

    /**
     * The base component document. Its bytes are the base's identity as far as Image Builder is
     * concerned: a component version is immutable, so re-registering the same version with a
     * different document is rejected, which is what {@code baas admin image build} preflights.
     *
     * <p>It deliberately carries nothing that depends on the extension — not even the image label —
     * so an extension edit never changes the base and never demands a hand-bumped version.
     */
    public String renderBase() {
        var tools = definition.tools();
        var kernel = definition.kernel();
        var asyncProfiler = tools.asyncProfiler();

        return requireFits("base", BASE_TEMPLATE
            .replace("{{IMAGE_VERSION}}", definition.imageVersion())
            .replace("{{CORRETTO_NVR}}", tools.corretto().nvr())
            .replace("{{PERF_PACKAGE}}", tools.perf().packageName())
            .replace("{{ASYNC_PROFILER_URL}}", asyncProfiler.downloadUrl())
            .replace("{{ASYNC_PROFILER_SHA256}}", asyncProfiler.sha256())
            .replace("{{ASYNC_PROFILER_HOME}}", asyncProfiler.installPath())
            .replace("{{PERF_EVENT_PARANOID}}", Integer.toString(kernel.perfEventParanoid()))
            .replace("{{KPTR_RESTRICT}}", Integer.toString(kernel.kptrRestrict()))
            .replace("{{TRANSPARENT_HUGEPAGES}}", kernel.transparentHugepages())
            .replace("{{SWAP_COMMANDS}}", kernel.swapDisabled() ? DISABLE_SWAP : KEEP_SWAP)
            .replace("{{JDKS_ROOT}}", Jdks.ROOT)
            .replace("{{DEFAULT_JDK}}", Jdks.DEFAULT_ID)
            .replace("{{JDK_ID_PATTERN}}", Jdks.ID_PATTERN.pattern())
            .replace("{{JDK_ID_MAX}}", Integer.toString(Jdks.MAX_ID_LENGTH)));
    }

    /**
     * The contract component document for an image carrying {@code label}. It writes the label —
     * the one value that depends on the extension, which is why the base must not — and, in the
     * {@code test} phase, checks the booted image for everything a BaaS workflow relies on, then
     * records the image's JDKs at {@link #jdkListKey} in the deployment's {@code bucket}, where
     * publishing the image reads them.
     */
    public String renderContract(String label, String bucket, String region) {
        var asyncProfiler = definition.tools().asyncProfiler();

        return requireFits("contract", CONTRACT_TEMPLATE
            .replace("{{JDK_LIST_URI}}", "s3://" + bucket + "/" + jdkListKey(label))
            .replace("{{REGION}}", region)
            .replace("{{JDKS_ROOT}}", Jdks.ROOT)
            .replace("{{DEFAULT_JDK}}", Jdks.DEFAULT_ID)
            .replace("{{JDK_ID_PATTERN}}", Jdks.ID_PATTERN.pattern())
            .replace("{{JDK_ID_MAX}}", Integer.toString(Jdks.MAX_ID_LENGTH))
            .replace("{{JDK_TAG_MAX}}", Integer.toString(JDKS_TAG_LIMIT))
            .replace("{{LABEL}}", label)
            .replace("{{JAVA_FLOOR}}", Integer.toString(javaFloor))
            .replace("{{PERF_PACKAGE}}", definition.tools().perf().packageName())
            .replace("{{ASYNC_PROFILER_LIB}}", asyncProfiler.libraryPath())
            .replace("{{ASPROF}}", asyncProfiler.installPath() + "/bin/asprof"));
    }

    /**
     * Where the contract records the JDKs of the image labelled {@code label}: beside Image Builder's
     * own logs, under the one prefix the build role may write.
     */
    public static String jdkListKey(String label) {
        return "image-builds/" + label + "/jdks.json";
    }

    /** The longest value an AMI tag holds, and so the longest {@code baas-jdks} list. */
    static final int JDKS_TAG_LIMIT = 256;

    private static String requireFits(String component, String document) {
        int size = document.getBytes(StandardCharsets.UTF_8).length;
        if (size > CFN_PARAMETER_LIMIT_BYTES) {
            throw new IllegalStateException(
                "Rendered %s component is %d bytes; CloudFormation caps a parameter value at %d. "
                    .formatted(component, size, CFN_PARAMETER_LIMIT_BYTES)
                    + "Shorten its steps in RunnerImageRenderer, or split it in two.");
        }
        return document;
    }

    private static int loadJavaFloor() {
        try (InputStream is = RunnerImageRenderer.class.getResourceAsStream(BUILD_PROPERTIES_RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException(BUILD_PROPERTIES_RESOURCE + " is not on the classpath");
            }
            var properties = new java.util.Properties();
            properties.load(is);
            return Integer.parseInt(properties.getProperty("runner.javaRelease").strip());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static RunnerImageDefinition load(String classpathResource) {
        try (InputStream is = RunnerImageRenderer.class.getResourceAsStream(classpathResource)) {
            if (is == null) {
                throw new IllegalStateException(classpathResource + " is not on the classpath");
            }
            return parse(is);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Fails on an unknown key (Jackson's default) and on a missing value ({@code requireComplete}). */
    static RunnerImageDefinition parse(InputStream yaml) throws IOException {
        try {
            return YAML.readValue(yaml, RunnerImageDefinition.class).requireComplete();
        } catch (com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException e) {
            String path = e.getPath().stream()
                .map(com.fasterxml.jackson.databind.JsonMappingException.Reference::getFieldName)
                .collect(java.util.stream.Collectors.joining("."));
            throw new IllegalStateException("runner-image.yaml has an unknown key '" + path
                + "' (known there: " + e.getKnownPropertyIds() + ")", e);
        }
    }

    /**
     * {@code set -euxo pipefail} is right here and wrong in {@code UserDataScriptBuilder}: a bake
     * that half-installs the toolchain must abort, whereas a runner that exits early orphans a
     * paid instance before its watchdog starts.
     *
     * <p>{@code perf} is installed for the running kernel: the build instance boots the parent, so
     * {@code uname -r} is the parent's kernel, and the RPM built from it is the only right one.
     */
    private static final String BASE_TEMPLATE = """
        name: baas-runner-toolchain
        description: BaaS runner measurement environment base {{IMAGE_VERSION}}
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
                      dnf install -y "{{PERF_PACKAGE}}-$(uname -r | sed 's/\\.x86_64$//')"
                      curl -fsSL '{{ASYNC_PROFILER_URL}}' -o /tmp/ap.tgz
                      echo '{{ASYNC_PROFILER_SHA256}}  /tmp/ap.tgz' | sha256sum -c -
                      rm -rf '{{ASYNC_PROFILER_HOME}}'
                      mkdir -p /app
                      tar -xf /tmp/ap.tgz -C /tmp
                      mv /tmp/async-profiler-*-linux-x64 '{{ASYNC_PROFILER_HOME}}'
                      rm -f /tmp/ap.tgz
                      mkdir -p '{{JDKS_ROOT}}'
                      ln -s "$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")" '{{JDKS_ROOT}}/{{DEFAULT_JDK}}'
              - name: InstallJdkHelper
                action: ExecuteBash
                inputs:
                  commands:
                    - |
                      set -euxo pipefail
                      cat > /usr/local/bin/baas-install-jdk <<'HELPER'
                      #!/bin/bash
                      # baas-install-jdk <id> <url> <sha256>: a JDK baas run --jdk <id> can select
                      set -euo pipefail
                      die() { echo "baas-install-jdk: $1" >&2; exit 1; }
                      [ $# -eq 3 ] || die "usage: baas-install-jdk <id> <url> <sha256>"
                      id=$1; root='{{JDKS_ROOT}}'
                      [[ $id =~ ^{{JDK_ID_PATTERN}}$ && ${#id} -le {{JDK_ID_MAX}} ]] || die "id '$id' must match {{JDK_ID_PATTERN}}, at most {{JDK_ID_MAX}} characters"
                      [ ! -e "$root/$id" ] || die "$root/$id already exists"
                      tmp=$(mktemp -d /opt/.baas-install-jdk.XXXXXX)
                      trap 'rm -rf "$tmp"' EXIT
                      curl -fsSL "$2" -o "$tmp/jdk.tar"
                      echo "$3  $tmp/jdk.tar" | sha256sum -c - || die "$2 does not have SHA-256 $3"
                      mkdir "$tmp/jdk"
                      tar -xf "$tmp/jdk.tar" -C "$tmp/jdk" --strip-components=1
                      [ -x "$tmp/jdk/bin/java" ] || die "the archive holds no bin/java"
                      [ -x "$tmp/jdk/bin/jcmd" ] || die "the archive holds no bin/jcmd: a JRE, not a JDK"
                      mv "$tmp/jdk" "$root/$id"
                      "$root/$id/bin/java" -version
                      HELPER
                      chmod 755 /usr/local/bin/baas-install-jdk
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
        """;

    /**
     * Runs after the extension. The build phase writes the label; the {@code test} phase runs on an
     * instance booted from the new image, which is the only place the applied sysctls, the THP
     * kernel argument and a kernel an extension upgraded are visible. Every check runs and reports
     * before the step fails, so one bake names every broken requirement rather than the first.
     *
     * <p>{@code java.specification.version} is read from the system properties rather than parsed
     * out of the {@code java -version} banner, whose wording differs between vendors.
     */
    private static final String CONTRACT_TEMPLATE = """
        name: baas-runner-contract
        description: BaaS runner contract for image {{LABEL}}
        schemaVersion: 1.0
        phases:
          - name: build
            steps:
              - name: WriteImageLabel
                action: ExecuteBash
                inputs:
                  commands:
                    - |
                      printf '%s\\n' '{{LABEL}}' > /etc/baas-image-version
          - name: test
            steps:
              - name: CheckBaasContract
                action: ExecuteBash
                inputs:
                  commands:
                    - |
                      failed=0
                      fail() { echo "BaaS contract FAILED: $1" >&2; failed=1; }
                      if command -v java >/dev/null; then
                        spec=$(java -XshowSettings:properties -version 2>&1 | awk -F' = ' '/java.specification.version/ {print $2; exit}')
                        [ "${spec%%.*}" -ge {{JAVA_FLOOR}} ] 2>/dev/null || fail "java on PATH is specification version '${spec}', below {{JAVA_FLOOR}}"
                      else
                        fail "no java on PATH"
                      fi
                      aws --version >/dev/null 2>&1 || fail "the aws CLI does not run"
                      systemctl is-enabled amazon-ssm-agent >/dev/null 2>&1 || fail "the amazon-ssm-agent service is not enabled, so baas jobs connect cannot reach runners"
                      test -f '{{ASYNC_PROFILER_LIB}}' || fail "no async-profiler library at {{ASYNC_PROFILER_LIB}}"
                      test -x '{{ASPROF}}' || fail "no asprof at {{ASPROF}}"
                      kernel=$(uname -r | sed 's/\\.x86_64$//')
                      rpm -q "{{PERF_PACKAGE}}-${kernel}" >/dev/null || fail "perf does not match the running kernel ${kernel}: $(rpm -q {{PERF_PACKAGE}})"
                      [ "$(cat /etc/baas-image-version 2>/dev/null)" = '{{LABEL}}' ] || fail "/etc/baas-image-version does not hold {{LABEL}}"
                      [ "$(sysctl -n kernel.perf_event_paranoid)" -le 1 ] || fail "kernel.perf_event_paranoid is $(sysctl -n kernel.perf_event_paranoid), above 1"
                      [ "$(sysctl -n kernel.kptr_restrict)" -eq 0 ] || fail "kernel.kptr_restrict is $(sysctl -n kernel.kptr_restrict), not 0"
                      root='{{JDKS_ROOT}}'; default='{{DEFAULT_JDK}}'; ids=; json=
                      [ -e "$root/$default" ] || fail "no default JDK $root/$default"
                      prop() { printf '%s\\n' "$p" | awk -F' = ' -v k="$1" '{ sub(/^ +/, "", $1) } $1 == k { print $2; exit }' | sed 's/[\\"]/\\\\&/g'; }
                      for id in "$default" $(ls -A "$root" | grep -vxF "$default"); do
                        [[ $id =~ ^{{JDK_ID_PATTERN}}$ && ${#id} -le {{JDK_ID_MAX}} ]] || { fail "JDK id '$id' in $root must match {{JDK_ID_PATTERN}}, at most {{JDK_ID_MAX}} characters"; continue; }
                        p=$("$root/$id/bin/java" -XshowSettings:properties -version 2>&1) || { fail "JDK $id: $root/$id/bin/java does not run"; continue; }
                        [ -x "$root/$id/bin/jcmd" ] || fail "JDK $id has no bin/jcmd"
                        json="$json${json:+,}$(printf '{"id":"%s","release":"%s","vendor":"%s","default":%s}' "$id" "$(prop java.version)" "$(prop java.vendor)" "$([ "$id" = "$default" ] && echo true || echo false)")"
                        ids="$ids${ids:+,}$id"
                      done
                      [ ${#ids} -le {{JDK_TAG_MAX}} ] || fail "the JDK ids take ${#ids} characters joined, above the {{JDK_TAG_MAX}} an AMI tag holds"
                      [ "$failed" -eq 0 ] || exit 1
                      printf '[%s]\\n' "$json" > /tmp/jdks.json
                      aws s3 cp /tmp/jdks.json '{{JDK_LIST_URI}}' --region '{{REGION}}' || { echo "BaaS contract FAILED: cannot write the JDK list" >&2; exit 1; }
                      echo "BaaS contract: every check passed"
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
