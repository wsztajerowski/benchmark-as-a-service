package pl.wsztajerowski.baas.results;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentManifestTest {

    private static final String BASE = """
        {
          "schemaVersion": 1,
          "imageVersion": "1.0.0",
          "amiId": "ami-aaa",
          "instanceType": "c5.2xlarge",
          "jvmVersion": "openjdk version \\"25.0.1\\" 2025-10-21",
          "kernelRelease": "6.1.177-224.371.amzn2023"
        }
        """;

    @Test
    void identicalManifestsReportNothing() {
        var a = EnvironmentManifest.parse("main/jmh/a", BASE);
        var b = EnvironmentManifest.parse("main/jmh/b", BASE);

        assertThat(EnvironmentManifest.diff(a, b)).isEmpty();
    }

    @Test
    void changedFieldIsReportedWithBothValues() {
        var a = EnvironmentManifest.parse("main/jmh/a", BASE);
        var b = EnvironmentManifest.parse("main/jmh/b", BASE.replace("25.0.1", "25.0.3"));

        assertThat(EnvironmentManifest.diff(a, b))
            .hasSize(1)
            .hasEntrySatisfying("jvmVersion", difference -> {
                assertThat(difference.left()).contains("25.0.1");
                assertThat(difference.right()).contains("25.0.3");
            });
    }

    /**
     * A field only one side carries is a difference in the environment record, and hiding it is
     * how a manifest schema change gets mistaken for a stable environment.
     */
    @Test
    void addedAndRemovedFieldsAreReported() {
        var a = EnvironmentManifest.parse("main/jmh/a", BASE);
        var b = EnvironmentManifest.parse("main/jmh/b",
            BASE.replace("\"kernelRelease\"", "\"perfVersion\": \"6.1.177\",\n  \"kernelRelease\""));

        var differences = EnvironmentManifest.diff(a, b);

        assertThat(differences).containsOnlyKeys("perfVersion");
        assertThat(differences.get("perfVersion").left()).isEmpty();
        assertThat(differences.get("perfVersion").right()).isEqualTo("6.1.177");
    }

    /**
     * A manifest written by a newer runner carries fields this CLI has never heard of, and those
     * are exactly the ones worth reporting — parsing into named fields would drop them silently.
     */
    @Test
    void unknownFieldsSurviveParsing() {
        var manifest = EnvironmentManifest.parse("main/jmh/a",
            BASE.replace("\"kernelRelease\"", "\"somethingAddedLater\": \"42\",\n  \"kernelRelease\""));

        assertThat(manifest.fields()).containsEntry("somethingAddedLater", "42");
    }

    @Test
    void anUnknownSchemaVersionIsStillParsed() {
        var manifest = EnvironmentManifest.parse("main/jmh/a", BASE.replace("\"schemaVersion\": 1", "\"schemaVersion\": 99"));

        assertThat(manifest.schemaVersion()).hasValue("99");
        assertThat(manifest.fields()).containsEntry("imageVersion", "1.0.0");
    }

    @Test
    void aManifestWithNoSchemaVersionStillDiffs() {
        var a = EnvironmentManifest.parse("main/jmh/a", "{\"imageVersion\": \"1.0.0\"}");
        var b = EnvironmentManifest.parse("main/jmh/b", "{\"imageVersion\": \"1.1.0\"}");

        assertThat(a.schemaVersion()).isEmpty();
        assertThat(EnvironmentManifest.diff(a, b)).containsOnlyKeys("imageVersion");
    }

    @Test
    void diffIsOrderedSoOutputIsStable() {
        var a = EnvironmentManifest.parse("main/jmh/a", BASE);
        var b = EnvironmentManifest.parse("main/jmh/b",
            BASE.replace("25.0.1", "25.0.3").replace("ami-aaa", "ami-bbb").replace("1.0.0", "1.1.0"));

        assertThat(EnvironmentManifest.diff(a, b).keySet())
            .containsExactly("amiId", "imageVersion", "jvmVersion");
    }
}
