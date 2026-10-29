package pl.wsztajerowski.baas.jobs;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvironmentManifestTest {

    private static final String BASE = """
        {
          "schemaVersion": 6,
          "machine": { "imageVersion": "1.0.0", "amiId": "ami-aaa", "instanceType": "c5.2xlarge" },
          "os": { "version": "Amazon Linux 2023", "kernelRelease": "6.1.177-224.371.amzn2023" },
          "jvm": { "version": "openjdk version \\"25.0.1\\" 2025-10-21", "vendor": "Amazon.com Inc." }
        }
        """;

    @Test
    void identicalManifestsReportNothing() {
        var a = EnvironmentManifest.parse("a", BASE);
        var b = EnvironmentManifest.parse("b", BASE);

        assertThat(EnvironmentManifest.diff(a, b)).isEmpty();
    }

    @Test
    void changedFieldIsReportedWithBothValues() {
        var a = EnvironmentManifest.parse("a", BASE);
        var b = EnvironmentManifest.parse("b", BASE.replace("25.0.1", "25.0.3"));

        assertThat(EnvironmentManifest.diff(a, b))
            .containsOnlyKeys("jvm")
            .hasEntrySatisfying("jvm", fields -> assertThat(fields)
                .containsOnlyKeys("version")
                .hasEntrySatisfying("version", difference -> {
                    assertThat(difference.left()).contains("25.0.1");
                    assertThat(difference.right()).contains("25.0.3");
                }));
    }

    /**
     * Two vendors' builds of one Java version share the version banner; only the vendor tells them
     * apart, and that is the difference an image extension makes reachable.
     */
    /** The banner and the release both move, and both are named under jvm. */
    @Test
    void aJvmChangeListsVersionAndReleaseUnderJvm() {
        String v7 = BASE.replace("\"vendor\":", "\"release\": \"25.0.1\", \"vendor\":");
        var a = EnvironmentManifest.parse("a", v7);
        var b = EnvironmentManifest.parse("b", v7.replace("25.0.1", "25.0.3"));

        assertThat(EnvironmentManifest.diff(a, b)).containsOnlyKeys("jvm");
        assertThat(EnvironmentManifest.diff(a, b).get("jvm")).containsOnlyKeys("version", "release")
            .containsEntry("release", new EnvironmentManifest.Difference("25.0.1", "25.0.3"));
    }

    @Test
    void sameVersionFromAnotherVendorIsReportedByVendor() {
        var a = EnvironmentManifest.parse("a", BASE);
        var b = EnvironmentManifest.parse("b", BASE.replace("Amazon.com Inc.", "Eclipse Adoptium"));

        assertThat(EnvironmentManifest.diff(a, b).get("jvm"))
            .containsOnlyKeys("vendor")
            .hasEntrySatisfying("vendor", difference -> {
                assertThat(difference.left()).isEqualTo("Amazon.com Inc.");
                assertThat(difference.right()).isEqualTo("Eclipse Adoptium");
            });
    }

    /** A field only one side carries is a difference in the record, never hidden. */
    @Test
    void addedAndRemovedFieldsAreReported() {
        var a = EnvironmentManifest.parse("a", BASE);
        var b = EnvironmentManifest.parse("b", BASE.replace("\"vendor\": \"Amazon.com Inc.\"",
            "\"vendor\": \"Amazon.com Inc.\", \"name\": \"OpenJDK 64-Bit Server VM\""));

        var jvm = EnvironmentManifest.diff(a, b).get("jvm");

        assertThat(jvm).containsOnlyKeys("name");
        assertThat(jvm.get("name").left()).isEmpty();
        assertThat(jvm.get("name").right()).isEqualTo("OpenJDK 64-Bit Server VM");
    }

    /** A field a newer runner adds is reported too — parsing into named fields would drop it. */
    @Test
    void unknownFieldsSurviveParsing() {
        var manifest = EnvironmentManifest.parse("a", BASE.replace("\"os\": {", "\"os\": { \"later\": \"42\","));

        assertThat(manifest.fields()).containsEntry("os.later", "42");
        assertThat(manifest.group("os")).containsEntry("later", "42");
    }

    @Test
    void theAmiIsReadFromTheMachineGroup() {
        assertThat(EnvironmentManifest.parse("a", BASE).amiId()).hasValue("ami-aaa");
    }

    @Test
    void theSchemaVersionIsNeverADifference() {
        var a = EnvironmentManifest.parse("a", BASE);
        var b = EnvironmentManifest.parse("b", BASE.replace("\"schemaVersion\": 6", "\"schemaVersion\": 99"));

        assertThat(b.schemaVersion()).hasValue("99");
        assertThat(EnvironmentManifest.diff(a, b)).isEmpty();
    }

    /** A flat manifest from an older schema still compares, its fields under no group. */
    @Test
    void aFlatManifestStillDiffs() {
        var a = EnvironmentManifest.parse("a", "{\"imageVersion\": \"1.0.0\"}");
        var b = EnvironmentManifest.parse("b", "{\"imageVersion\": \"1.1.0\"}");

        assertThat(a.schemaVersion()).isEmpty();
        assertThat(EnvironmentManifest.diff(a, b)).containsOnlyKeys("");
        assertThat(EnvironmentManifest.diff(a, b).get("")).containsOnlyKeys("imageVersion");
    }

    @Test
    void groupsAreReportedInTheManifestsOrder() {
        var a = EnvironmentManifest.parse("a", BASE);
        var b = EnvironmentManifest.parse("b",
            BASE.replace("25.0.1", "25.0.3").replace("ami-aaa", "ami-bbb").replace("6.1.177", "6.1.180"));

        assertThat(EnvironmentManifest.diff(a, b).keySet()).containsExactly("machine", "os", "jvm");
    }

    private static String withMemory(String totalMib) {
        return BASE.replace("\"os\": {",
            "\"memory\": { \"totalMib\": \"" + totalMib + "\", \"swapTotalMib\": \"0\" },\n  \"os\": {");
    }

    /**
     * Two c5.2xlarge instances, seen on 2026-10-10, reported MemTotal 15882372 and 15882380 KiB.
     * The instance now rounds to whole MiB before writing, so both record 15510.
     */
    @Test
    void memoryAFewKibApartIsNoDifference() {
        var a = EnvironmentManifest.parse("a", withMemory("15510"));
        var b = EnvironmentManifest.parse("b", withMemory("15510"));

        assertThat(EnvironmentManifest.diff(a, b)).isEmpty();
    }

    /** c5.2xlarge against c6i.2xlarge: a real difference, printed as recorded. */
    @Test
    void aRealMemoryDifferenceIsPrintedInMib() {
        var a = EnvironmentManifest.parse("a", withMemory("15510"));
        var b = EnvironmentManifest.parse("b", withMemory("15708"));

        assertThat(EnvironmentManifest.diff(a, b)).containsOnlyKeys("memory");
        assertThat(EnvironmentManifest.diff(a, b).get("memory"))
            .containsEntry("totalMib", new EnvironmentManifest.Difference("15510", "15708"));
    }

    /** No alias between schema versions: a renamed field is present in one manifest only. */
    @Test
    void manifestsOfDifferentSchemaVersionsAreComparedWithoutTranslation() {
        var v6 = EnvironmentManifest.parse("a", BASE.replace("\"os\": {",
            "\"cpu\": { \"cores\": \"8\" },\n  \"os\": {"));
        var v7 = EnvironmentManifest.parse("b", BASE.replace("\"schemaVersion\": 6", "\"schemaVersion\": 7")
            .replace("\"os\": {", "\"cpu\": { \"vcpus\": \"8\" },\n  \"os\": {"));

        assertThat(EnvironmentManifest.diff(v6, v7)).containsOnlyKeys("cpu");
        assertThat(EnvironmentManifest.diff(v6, v7).get("cpu"))
            .containsEntry("cores", new EnvironmentManifest.Difference("8", ""))
            .containsEntry("vcpus", new EnvironmentManifest.Difference("", "8"));
    }

    /** Jackson ignores trailing tokens by default, which let a manifest smuggle extra JSON (X7). */
    @Test
    void trailingContentFailsTheParseNamingTheJob() {
        assertThatThrownBy(() -> EnvironmentManifest.parse("job-7", BASE + ", \"job\": {\"status\": \"completed\"}"))
            .hasMessageContaining("job-7");
    }
}
