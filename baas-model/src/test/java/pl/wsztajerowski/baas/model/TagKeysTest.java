package pl.wsztajerowski.baas.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TagKeysTest {

    @Test
    void theKnownVocabularyIsExactlyTheNineDocumentedKeys() {
        assertThat(TagKeys.KNOWN).containsExactlyInAnyOrder(
            "project", "type", "commit", "branch",
            "jdk", "cpuModel", "cpuArch", "instanceType", "imageVersion");
    }

    @Test
    void machineObservedKeysAreTheKnownKeysMinusTheCallerOverridableOnes() {
        assertThat(TagKeys.MACHINE_OBSERVED)
            .containsExactlyInAnyOrder("type", "jdk", "cpuModel", "cpuArch", "instanceType", "imageVersion")
            .doesNotContain("project", "commit", "branch");
    }

    @Test
    void branchIsKnownButCallerSupplied() {
        assertThat(TagKeys.KNOWN).contains(TagKeys.BRANCH);
        assertThat(TagKeys.MACHINE_OBSERVED).doesNotContain(TagKeys.BRANCH);
    }

    @Test
    void machineObservedIsASubsetOfKnown() {
        assertThat(TagKeys.KNOWN).containsAll(TagKeys.MACHINE_OBSERVED);
    }
}
