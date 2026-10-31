package pl.wsztajerowski.baas.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TagKeysTest {

    @Test
    void theKnownVocabularyIsExactlyTheFourteenDocumentedKeys() {
        assertThat(TagKeys.KNOWN).containsExactlyInAnyOrder(
            "project", "type", "commit", "branch", "source", "cliParameters", "profilers",
            "jdkId", "jdkVersion", "jdkVendor", "cpuModel", "cpuArch", "instanceType", "imageVersion");
    }

    @Test
    void machineObservedKeysAreTheKnownKeysMinusTheCallerOverridableOnes() {
        assertThat(TagKeys.MACHINE_OBSERVED)
            .containsExactlyInAnyOrder("type", "jdkId", "jdkVersion", "jdkVendor", "cpuModel", "cpuArch", "instanceType",
                "imageVersion")
            .doesNotContain("project", "commit", "branch", "source");
    }

    @Test
    void reservedKeysAreTheObservedOnesAndTheRecordedArguments() {
        assertThat(TagKeys.RESERVED)
            .containsAll(TagKeys.MACHINE_OBSERVED)
            .contains("cliParameters", "profilers")
            .hasSize(TagKeys.MACHINE_OBSERVED.size() + 2);
        assertThat(TagKeys.MACHINE_OBSERVED).doesNotContain("cliParameters", "profilers");
    }

    /** JDK names what was installed, chosen and ran; the pre-9.0.0 names are gone, not aliased. */
    @Test
    void theJdkKeysReplaceTheOldNames() {
        assertThat(TagKeys.KNOWN).doesNotContain("jdk", "jvmVendor");
        assertThat(TagKeys.RESERVED).contains("jdkId", "jdkVersion", "jdkVendor");
    }

    @Test
    void branchIsKnownButCallerSupplied() {
        assertThat(TagKeys.KNOWN).contains(TagKeys.BRANCH);
        assertThat(TagKeys.MACHINE_OBSERVED).doesNotContain(TagKeys.BRANCH);
    }

    /**
     * How a job was triggered is not something the instance observes, so reserving it would buy no
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

    @Test
    void aTagPairSplitsAtTheFirstEquals() {
        assertThat(TagKeys.parsePair("options=-f 1 -i=3")).containsExactly("options", "-f 1 -i=3");
    }

    @Test
    void aTagPairNeedsBothSides() {
        for (String bad : new String[]{"branch", "=main", "branch=", null}) {
            assertThatThrownBy(() -> TagKeys.parsePair(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected key=value");
        }
    }
}
