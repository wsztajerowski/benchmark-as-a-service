package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static pl.wsztajerowski.baas.infra.RunnerImageParameters.*;

class RunnerImageParametersTest {

    private static final String PARENT = "ami-070cc8ab883065d64";
    private static final String EXTENSION = "name: ext\nschemaVersion: 1.0\nphases: []\n";

    private final RunnerImageRenderer renderer = new RunnerImageRenderer();
    private final String base = renderer.renderBase();

    private RunnerImageParameters plan(String extension, Map<String, String> deployed) {
        return RunnerImageParameters.plan(renderer, "1.3.0", base, PARENT, extension, deployed);
    }

    @Test
    void aNewStackStartsEveryDerivedVersionAtTheFirst() {
        var plan = plan("", Map.of());

        assertThat(plan.values())
            .containsEntry(IMAGE_VERSION, "1.3.0")
            .containsEntry(RECIPE_VERSION, FIRST_VERSION)
            .containsEntry(EXTENSION_VERSION, FIRST_VERSION)
            .containsEntry(EXTENSION_DATA, "")
            .containsEntry(LABEL, "1.3.0")
            .containsKeys(ALL.toArray(String[]::new));
    }

    @Test
    void anUnchangedImageKeepsEveryVersion() {
        var deployed = plan(EXTENSION, Map.of()).values();

        assertThat(plan(EXTENSION, deployed).values())
            .as("a rebuild with no edit must not replace anything")
            .isEqualTo(deployed);
    }

    /** What a real stack returns: DescribeStacks drops each value's trailing newline. */
    @Test
    void aStackThatDroppedTrailingNewlinesIsStillUnchanged() {
        var deployed = new HashMap<String, String>();
        plan(EXTENSION, Map.of()).values().forEach((key, value) -> deployed.put(key, value.stripTrailing()));

        assertThat(plan(EXTENSION, deployed).values())
            .containsEntry(RECIPE_VERSION, deployed.get(RECIPE_VERSION))
            .containsEntry(EXTENSION_VERSION, deployed.get(EXTENSION_VERSION));
    }

    @Test
    void anExtensionEditMovesTheExtensionAndTheRecipeButNotTheBase() {
        var deployed = plan(EXTENSION, Map.of()).values();
        var edited = plan(EXTENSION.replace("ext", "ext2"), deployed).values();

        assertThat(edited)
            .containsEntry(IMAGE_VERSION, "1.3.0")
            .containsEntry(EXTENSION_VERSION, "1.0.1")
            .containsEntry(RECIPE_VERSION, "1.0.1");
        assertThat(edited.get(LABEL)).startsWith("1.3.0+ext.").isNotEqualTo(deployed.get(LABEL));
    }

    @Test
    void aBaseChangeMovesTheRecipeButNotTheExtension() {
        var deployed = new HashMap<>(plan(EXTENSION, Map.of()).values());
        deployed.put(COMPONENT_DATA, "an older base");
        deployed.put(IMAGE_VERSION, "1.2.0");

        assertThat(plan(EXTENSION, deployed).values())
            .containsEntry(EXTENSION_VERSION, FIRST_VERSION)
            .containsEntry(RECIPE_VERSION, "1.0.1");
    }

    /**
     * A stack from before this change has only the base's three parameters, and its recipe was
     * versioned by the base's version — so that is the registered recipe version to move past.
     */
    @Test
    void aPreChangeStackMovesPastTheRecipeVersionItWasVersionedWith() {
        Map<String, String> preChange = Map.of(
            IMAGE_VERSION, "1.2.0", PARENT_AMI_ID, PARENT, COMPONENT_DATA, "the 1.2.0 component");

        var plan = RunnerImageParameters.plan(renderer, "1.2.0", "the 1.2.0 component", PARENT, "", preChange);

        assertThat(plan.values()).containsEntry(RECIPE_VERSION, "1.2.1").containsEntry(LABEL, "1.2.0");
        assertThat(plan.absentFrom(preChange))
            .as("on update, setup sends only what the stack lacks and carries the base forward")
            .containsOnlyKeys(EXTENSION_DATA, EXTENSION_VERSION, CONTRACT_DATA, RECIPE_VERSION, LABEL);
    }

    @Test
    void theLabelNamesTheExtensionByItsHash() {
        assertThat(RunnerImageParameters.label("1.3.0", "")).isEqualTo("1.3.0");
        assertThat(RunnerImageParameters.label("1.3.0", EXTENSION))
            .isEqualTo("1.3.0+ext." + RunnerImageExtension.hash(EXTENSION));
    }

    @Test
    void theContractCarriesTheLabel() {
        var plan = plan(EXTENSION, Map.of());

        assertThat(plan.values().get(CONTRACT_DATA)).contains("'" + plan.label() + "'");
    }
}
