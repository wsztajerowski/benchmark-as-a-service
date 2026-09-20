package pl.wsztajerowski.baas.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TagKeysTest {

    @Test
    void theKnownVocabularyIsExactlyTheTenDocumentedKeys() {
        assertThat(TagKeys.KNOWN).containsExactlyInAnyOrder(
            "project", "type", "commit", "branch", "source",
            "jdk", "cpuModel", "cpuArch", "instanceType", "imageVersion");
    }

    @Test
    void machineObservedKeysAreTheKnownKeysMinusTheCallerOverridableOnes() {
        assertThat(TagKeys.MACHINE_OBSERVED)
            .containsExactlyInAnyOrder("type", "jdk", "cpuModel", "cpuArch", "instanceType", "imageVersion")
            .doesNotContain("project", "commit", "branch", "source");
    }

    @Test
    void branchIsKnownButCallerSupplied() {
        assertThat(TagKeys.KNOWN).contains(TagKeys.BRANCH);
        assertThat(TagKeys.MACHINE_OBSERVED).doesNotContain(TagKeys.BRANCH);
    }

    /**
     * How a run was triggered is not something the instance observes, so reserving it would buy no
     * protection — the reserved keys exist to stop a result's tags disagreeing with its own
     * environment.json. Leaving it caller-overridable is what lets a consumer label a nightly or
     * release run.
     */
    @Test
    void sourceIsKnownButCallerSupplied() {
        assertThat(TagKeys.KNOWN).contains(TagKeys.SOURCE);
        assertThat(TagKeys.MACHINE_OBSERVED).doesNotContain(TagKeys.SOURCE);
    }

    @Test
    void theDerivedSourceValuesAreCiAndLocal() {
        assertThat(TagKeys.SOURCE_CI).isEqualTo("ci");
        assertThat(TagKeys.SOURCE_LOCAL).isEqualTo("local");
    }

    @Test
    void machineObservedIsASubsetOfKnown() {
        assertThat(TagKeys.KNOWN).containsAll(TagKeys.MACHINE_OBSERVED);
    }
}
