package pl.wsztajerowski.baas.infra;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Every stack parameter that describes the runner image, and the label results will carry.
 *
 * <p>Only the base's version is written by hand ({@code imageVersion} in {@code runner-image.yaml}).
 * Image Builder also needs a version for the extension and the recipe, numeric and never reused while
 * registered; those are derived here from what the deployed stack holds, so an operator editing an
 * extension never has to version it. CloudFormation replaces a component or recipe whose content
 * changed and deletes the old one, so the deployed value is always the one registered version, and
 * the next one cannot collide with it.
 *
 * <p>The contract's version is the recipe's: the contract carries the label, which changes only
 * when the recipe does.
 */
public record RunnerImageParameters(Map<String, String> values, String label) {

    public static final String IMAGE_VERSION = "RunnerImageVersion";
    public static final String PARENT_AMI_ID = "RunnerParentAmiId";
    public static final String COMPONENT_DATA = "RunnerImageComponentData";
    public static final String EXTENSION_DATA = "RunnerImageExtensionData";
    public static final String EXTENSION_VERSION = "RunnerImageExtensionVersion";
    public static final String CONTRACT_DATA = "RunnerImageContractData";
    public static final String RECIPE_VERSION = "RunnerImageRecipeVersion";
    public static final String LABEL = "RunnerImageLabel";

    public static final List<String> ALL = List.of(IMAGE_VERSION, PARENT_AMI_ID, COMPONENT_DATA,
        EXTENSION_DATA, EXTENSION_VERSION, CONTRACT_DATA, RECIPE_VERSION, LABEL);

    static final String FIRST_VERSION = "1.0.0";

    /**
     * Plans the image parameters for one stack submission.
     *
     * @param baseVersion the base component's version
     * @param baseData    the base component's document
     * @param parentAmiId the parent AMI the recipe builds from
     * @param extension   the extension to deploy, empty for none
     * @param deployed    the deployed stack's parameters; empty when the stack is being created
     */
    public static RunnerImageParameters plan(RunnerImageRenderer renderer, String baseVersion, String baseData,
                                             String parentAmiId, String extension,
                                             Map<String, String> deployed) {
        String label = label(baseVersion, extension);
        String contract = renderer.renderContract(label);

        boolean extensionChanged = !sameDocument(extension, deployed.getOrDefault(EXTENSION_DATA, ""));
        boolean recipeChanged = extensionChanged
            || !Objects.equals(baseVersion, deployed.get(IMAGE_VERSION))
            || !sameDocument(baseData, deployed.get(COMPONENT_DATA))
            || !Objects.equals(parentAmiId, deployed.get(PARENT_AMI_ID))
            || !sameDocument(contract, deployed.get(CONTRACT_DATA));

        // A stack from before the recipe had its own version was versioned by the base's, so that
        // is the one registered and the one to move past.
        String recipeVersion = next(deployed.getOrDefault(RECIPE_VERSION, deployed.get(IMAGE_VERSION)),
            recipeChanged);
        String extensionVersion = next(deployed.get(EXTENSION_VERSION), extensionChanged);

        Map<String, String> values = new LinkedHashMap<>();
        values.put(IMAGE_VERSION, baseVersion);
        values.put(PARENT_AMI_ID, parentAmiId);
        values.put(COMPONENT_DATA, baseData);
        values.put(EXTENSION_DATA, extension);
        values.put(EXTENSION_VERSION, extensionVersion);
        values.put(CONTRACT_DATA, contract);
        values.put(RECIPE_VERSION, recipeVersion);
        values.put(LABEL, label);
        return new RunnerImageParameters(values, label);
    }

    /**
     * {@code <base>} without an extension, {@code <base>+ext.<hash>} with one. An extended image's
     * results can therefore never share an {@code imageVersion} tag with a stock image's, and one
     * extension is labelled alike in every installation.
     */
    public static String label(String baseVersion, String extension) {
        return extension.isEmpty() ? baseVersion : baseVersion + "+ext." + RunnerImageExtension.hash(extension);
    }

    /** The parameters a deployed stack does not have yet — the only ones setup sends on an update. */
    public Map<String, String> absentFrom(Map<String, String> deployed) {
        Map<String, String> absent = new LinkedHashMap<>(values);
        absent.keySet().removeAll(deployed.keySet());
        return absent;
    }

    /**
     * DescribeStacks returns a parameter without its trailing newline, and every rendered document
     * ends with one. Compared exactly, every build looked changed and replaced the contract and the
     * recipe for nothing — found live, recipe 1.2.4 to 1.2.5 on a rebuild with no edit.
     */
    private static boolean sameDocument(String rendered, String deployed) {
        return deployed != null && rendered.stripTrailing().equals(deployed.stripTrailing());
    }

    private static String next(String previous, boolean changed) {
        if (previous == null || previous.isEmpty()) {
            return FIRST_VERSION;
        }
        if (!changed) {
            return previous;
        }
        String[] parts = previous.split("\\.");
        if (parts.length != 3) {
            throw new IllegalStateException("Deployed image version '" + previous + "' is not x.y.z");
        }
        return parts[0] + "." + parts[1] + "." + (Long.parseLong(parts[2]) + 1);
    }
}
