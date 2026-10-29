package pl.wsztajerowski.baas.jobs;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Every form shortens what it recognises and prints anything else as recorded. */
class EnvironmentViewTest {

    private static Map<String, List<String>> view(String groups) {
        return EnvironmentView.of(EnvironmentManifest.parse("job", "{\"schemaVersion\": 7, " + groups + "}"));
    }

    @Test
    void memoryIsShownInGibAndSwapByItsSize() {
        assertThat(view("\"memory\": {\"totalMib\": \"15622\", \"swapTotalMib\": \"2048\"}").get("memory"))
            .containsExactly("15.3 GiB", "swap 2.0 GiB");
    }

    @Test
    void memoryThatIsNotANumberIsShownAsRecorded() {
        assertThat(view("\"memory\": {\"totalMib\": \"unknown\", \"swapTotalMib\": \"0\"}").get("memory"))
            .containsExactly("totalMib unknown", "no swap");
    }

    @Test
    void aJvmWithoutAVendorVersionKeepsItsBannerReleaseAndVendor() {
        assertThat(view("\"jvm\": {\"version\": \"openjdk version \\\"25.0.4\\\"\", \"release\": \"25.0.4\", "
            + "\"vendor\": \"Eclipse Adoptium\"}").get("jvm"))
            .containsExactly("version openjdk version \"25.0.4\"", "release 25.0.4", "vendor Eclipse Adoptium");
    }

    @Test
    void aVendorVersionWithoutAVendorStandsAlone() {
        assertThat(view("\"jvm\": {\"vendorVersion\": \"Temurin-25.0.4+7\"}").get("jvm"))
            .containsExactly("Temurin 25.0.4+7");
    }

    @Test
    void toolBannersThatDoNotMatchAreShownAsRecorded() {
        assertThat(view("\"tools\": {\"perf\": \"absent\", \"asyncProfiler\": \"absent\"}").get("tools"))
            .containsExactly("perf absent", "asyncProfiler absent");
    }

    @Test
    void transparentHugepagesWithoutASelectionIsShownAsRecorded() {
        assertThat(view("\"tunables\": {\"transparentHugepages\": \"never\"}").get("tunables"))
            .containsExactly("transparentHugepages never");
        assertThat(view("\"tunables\": {\"transparentHugepages\": \"always [madvise] never\"}").get("tunables"))
            .containsExactly("thp madvise");
    }

    @Test
    void aGroupWithOnlyEmptyOrRepeatedValuesIsLeftOut() {
        assertThat(view("\"machine\": {\"instanceType\": \"c5.2xlarge\"}, \"cpu\": {\"maxMhz\": \"\"}"))
            .doesNotContainKeys("machine", "cpu");
    }

    /** A v6 manifest is not translated: its fields print under their own names. */
    @Test
    void aSchemaSixManifestPrintsItsOwnFieldNames() {
        assertThat(view("\"cpu\": {\"cores\": \"8\"}, \"memory\": {\"totalKb\": \"15997068\"}"))
            .containsEntry("cpu", List.of("cores 8"))
            .containsEntry("memory", List.of("totalKb 15997068"));
    }

    @Test
    void aFieldNameIsFilteredLikeItsValue() {
        assertThat(view("\"os\": {\"ver\\u001b[2Jsion\": \"AL2023\"}").get("os"))
            .containsExactly("ver[2Jsion AL2023");
    }
}
