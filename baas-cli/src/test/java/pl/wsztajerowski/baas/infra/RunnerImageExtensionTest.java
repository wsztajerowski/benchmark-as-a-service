package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunnerImageExtensionTest {

    private static final String EXTENSION = """
        # Observability for the lab.
        name: runner-extension
        schemaVersion: 1.0
        phases:
          - name: build
            steps:
              - name: InstallOtelCollector
                action: ExecuteBash
                inputs:
                  commands:
                    - dnf install -y otelcol
              - name: InstallBpftrace
                action: ExecuteBash
                inputs:
                  commands:
                    - dnf install -y bpftrace
          - name: validate
            steps:
              - name: CheckOtel
                action: ExecuteBash
                inputs:
                  commands:
                    - otelcol --version
        """;

    // ─── Parsing a working copy ──────────────────────────────────────────────────

    @Test
    void theMarkerIsStrippedAndEverythingElseKeptAsWritten() {
        var copy = RunnerImageExtension.parse("# baas-extension-base: 3f9a1c2e\n" + EXTENSION);

        assertThat(copy.baseMarker()).contains("3f9a1c2e");
        assertThat(copy.content())
            .as("comments included: a pull has to give back what was pushed")
            .isEqualTo(EXTENSION.stripTrailing());
    }

    /** CloudFormation returns a parameter without its trailing newline; store what will read back. */
    @Test
    void trailingWhitespaceIsNotStored() {
        assertThat(RunnerImageExtension.parse(EXTENSION + "\n\n").content()).isEqualTo(EXTENSION.stripTrailing());
    }

    @Test
    void aFileWithoutAMarkerHasNone() {
        var copy = RunnerImageExtension.parse(EXTENSION);

        assertThat(copy.baseMarker()).isEmpty();
        assertThat(copy.content()).isEqualTo(EXTENSION.stripTrailing());
    }

    @Test
    void aFileOfCommentsOnlyIsNoExtension() {
        assertThat(RunnerImageExtension.parse(RunnerImageExtension.withMarker("")).content())
            .as("the starter, pushed unchanged, must deploy nothing")
            .isEmpty();
        assertThat(RunnerImageExtension.parse("# just a note\n\n   # indented\n").content()).isEmpty();
    }

    @Test
    void anOversizedExtensionIsRefusedNamingSizeAndLimit() {
        String large = "#" + "x".repeat(5000) + "\nname: big\n";

        assertThatThrownBy(() -> RunnerImageExtension.requireStorable(large))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(Integer.toString(large.length()))
            .hasMessageContaining("4096");
    }

    /**
     * Found live: DescribeStacks returns a non-ASCII character as '?', so the stack's copy — which
     * every reader hashes — differed from the one the image was baked and labelled with.
     */
    @Test
    void nonAsciiIsRefusedByLineAndColumn() {
        assertThatThrownBy(() -> RunnerImageExtension.requireStorable("name: x\n# a note \u2014 here\n"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("U+2014")
            .hasMessageContaining("line 2, column 10");
    }

    @Test
    void theStarterIsStorableAsWritten() {
        String starter = RunnerImageExtension.withMarker("");

        assertThat(starter.chars().allMatch(c -> c == '\n' || (c >= 0x20 && c <= 0x7E)))
            .as("an operator keeps the starter's comments when they uncomment the example")
            .isTrue();
    }

    // ─── Identity ────────────────────────────────────────────────────────────────

    @Test
    void theHashIsStableAcrossATrailingNewline() {
        assertThat(RunnerImageExtension.hash(EXTENSION))
            .as("Image Builder drops a trailing newline when it stores a document")
            .isEqualTo(RunnerImageExtension.hash(EXTENSION.stripTrailing()))
            .isEqualTo(RunnerImageExtension.hash(EXTENSION + "\n\n"))
            .matches("[0-9a-f]{8}");
    }

    @Test
    void anyOtherEditChangesTheHash() {
        assertThat(RunnerImageExtension.hash(EXTENSION.replace("bpftrace", "bcc")))
            .isNotEqualTo(RunnerImageExtension.hash(EXTENSION));
    }

    @Test
    void aPullRoundTripsThroughAPush() {
        String pulled = RunnerImageExtension.withMarker(EXTENSION);
        var copy = RunnerImageExtension.parse(pulled);

        assertThat(copy.content()).isEqualTo(EXTENSION.stripTrailing());
        assertThatCode(() -> RunnerImageExtension.requireCurrent(copy, EXTENSION.stripTrailing()))
            .as("pulling and pushing back unchanged must be accepted")
            .doesNotThrowAnyException();
    }

    @Test
    void aPullOfNothingPrintsTheStarterMarkedNone() {
        assertThat(RunnerImageExtension.withMarker(""))
            .startsWith("# baas-extension-base: none\n")
            .contains("baas admin build-image --extension")
            .contains("Never put a credential here");
    }

    // ─── The stale-push guard ────────────────────────────────────────────────────

    private static RunnerImageExtension.WorkingCopy file(String marker) {
        return new RunnerImageExtension.WorkingCopy(Optional.ofNullable(marker), "name: mine\n");
    }

    @Test
    void withNoExtensionDeployedAnyFileIsAccepted() {
        assertThatCode(() -> RunnerImageExtension.requireCurrent(file("none"), "")).doesNotThrowAnyException();
        assertThatCode(() -> RunnerImageExtension.requireCurrent(file(null), "")).doesNotThrowAnyException();
        assertThatCode(() -> RunnerImageExtension.requireCurrent(file("3f9a1c2e"), ""))
            .as("a file kept across a teardown and setup names an extension the new stack never had")
            .doesNotThrowAnyException();
    }

    @Test
    void aFilePulledFromTheDeployedExtensionIsAccepted() {
        String deployedHash = RunnerImageExtension.hash(EXTENSION);

        assertThatCode(() -> RunnerImageExtension.requireCurrent(file(deployedHash), EXTENSION))
            .doesNotThrowAnyException();
    }

    @Test
    void aFilePulledFromAReplacedExtensionIsRefused() {
        String deployedHash = RunnerImageExtension.hash(EXTENSION);

        assertThatThrownBy(() -> RunnerImageExtension.requireCurrent(file("3f9a1c2e"), EXTENSION))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(deployedHash)
            .hasMessageContaining("was pulled from 3f9a1c2e")
            .hasMessageContaining("baas admin image --extension");
    }

    @Test
    void aFileWithNoMarkerIsRefusedOnceAnExtensionIsDeployed() {
        assertThatThrownBy(() -> RunnerImageExtension.requireCurrent(file(null), EXTENSION))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("carries no '# baas-extension-base:' line");
    }

    // ─── Reporting ───────────────────────────────────────────────────────────────

    @Test
    void stepNamesAreGroupedByPhase() {
        assertThat(RunnerImageExtension.stepsByPhase(EXTENSION)).isEqualTo(Map.of(
            "build", List.of("InstallOtelCollector", "InstallBpftrace"),
            "validate", List.of("CheckOtel")));
    }

    @Test
    void anUnparseableDocumentReportsNoSteps() {
        assertThat(RunnerImageExtension.stepsByPhase("phases: [unclosed")).isEmpty();
    }
}
