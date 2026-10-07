package pl.wsztajerowski.baas.config;

import java.util.List;

/**
 * Every name the CLI composes from a deployment's prefix — the one place the composition rule
 * ({@code <prefix>} or {@code <prefix>-<type>-<name>}) is spelled out in Java.
 *
 * <p>Hand-built copies used to live beside {@link BaasConfig}'s derivations in four commands (finding
 * C5). None was wrong, but a drifted copy fails silently: a teardown retiring a pointer that does not
 * exist, or a preflight probing a role the stack never creates. It is also where the longest
 * composed role name is known, which bounds how long a deployment name may be
 * ({@link #MAX_PREFIX_LENGTH}).
 */
public record DeploymentNames(String prefix) {

    /** IAM's limit on a role name, the tightest limit any composed name is under. */
    static final int IAM_ROLE_NAME_LIMIT = 64;

    static final String RUNNER_ROLE_SUFFIX = "-role-runner";
    static final String OPERATOR_ROLE_SUFFIX = "-role-operator";
    static final String IMAGE_BUILD_ROLE_SUFFIX = "-role-image-build";

    /** Every role suffix the core stack appends. A test holds this equal to the template's. */
    public static final List<String> ROLE_SUFFIXES =
        List.of(RUNNER_ROLE_SUFFIX, OPERATOR_ROLE_SUFFIX, IMAGE_BUILD_ROLE_SUFFIX);

    /** The longest prefix whose every composed role name still fits IAM's 64 characters. */
    public static final int MAX_PREFIX_LENGTH = IAM_ROLE_NAME_LIMIT
        - ROLE_SUFFIXES.stream().mapToInt(String::length).max().orElseThrow();

    public static DeploymentNames of(String prefix) {
        return new DeploymentNames(prefix);
    }

    /** The core stack. Identical to the prefix — the stack is the deployment. */
    public String stack() { return prefix; }

    public String bucket() { return prefix; }

    public String resultsTable() { return prefix + "-results"; }

    public String amiPointer() { return "/" + prefix + "/runner/ami-id"; }

    public String runnerRole() { return prefix + RUNNER_ROLE_SUFFIX; }

    public String operatorRole() { return prefix + OPERATOR_ROLE_SUFFIX; }

    public String imageBuildRole() { return prefix + IMAGE_BUILD_ROLE_SUFFIX; }

    public String runnerInstanceProfile() { return prefix + "-profile-runner"; }

    public String runnerComponent() { return prefix + "-component-runner"; }

    public String runnerRecipe() { return prefix + "-recipe-runner"; }
}
