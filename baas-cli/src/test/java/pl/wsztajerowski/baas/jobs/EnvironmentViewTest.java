package pl.wsztajerowski.baas.jobs;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Every form shortens what it recognises and prints anything else as recorded. */
class EnvironmentViewTest {

    private static Map<String, List<String>> view(String groups) {
        return EnvironmentView.of(EnvironmentManifest.parse("job", "{\"schemaVersion\": 8, " + groups + "}"));
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
    void aJdkWithoutAVendorVersionKeepsItsBannerReleaseAndVendor() {
        assertThat(view("\"jdk\": {\"version\": \"openjdk version \\\"25.0.4\\\"\", \"release\": \"25.0.4\", "
            + "\"vendor\": \"Eclipse Adoptium\"}").get("jdk"))
            .containsExactly("version openjdk version \"25.0.4\"", "release 25.0.4", "vendor Eclipse Adoptium");
    }

    @Test
    void aVendorVersionWithoutAVendorStandsAlone() {
        assertThat(view("\"jdk\": {\"vendorVersion\": \"Temurin-25.0.4+7\"}").get("jdk"))
            .containsExactly("Temurin 25.0.4+7");
    }

    /** The id --jdk selected it by comes first; the home is that id's directory, so not repeated. */
    @Test
    void aSelectedJdkShowsItsIdAndItsBuild() {
        assertThat(view("\"jdk\": {\"id\": \"corretto-25\", \"home\": \"/opt/jdks/corretto-25\", "
            + "\"version\": \"openjdk version \\\"25.0.4\\\"\", \"release\": \"25.0.4\", \"vendor\": \"Amazon.com Inc.\", "
            + "\"vendorVersion\": \"Corretto-25.0.4.7.1\", \"vmName\": \"OpenJDK 64-Bit Server VM\"}").get("jdk"))
            .containsExactly("corretto-25", "Corretto 25.0.4.7.1 (Amazon.com Inc.)", "vmName OpenJDK 64-Bit Server VM");
    }

    /** An image from before JDK selection records no id; whatever home there is stays visible. */
    @Test
    void aJdkWithoutAnIdShowsItsHomeAsRecorded() {
        assertThat(view("\"jdk\": {\"id\": \"\", \"home\": \"/usr/lib/jvm/x\", \"vendorVersion\": \"Corretto-25.0.4.7.1\"}")
            .get("jdk"))
            .containsExactly("home /usr/lib/jvm/x", "Corretto 25.0.4.7.1");
    }

    @Test
    void theRunnersJdkIsShownByItsBuild() {
        assertThat(view("\"runnerJdk\": {\"release\": \"25.0.4\", \"vendorVersion\": \"Corretto-25.0.4.7.1\"}")
            .get("runnerJdk"))
            .containsExactly("Corretto 25.0.4.7.1");
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
