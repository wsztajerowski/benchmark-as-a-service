package pl.wsztajerowski.baas.image;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One runner-image build's order, shared by {@code admin image build} and setup (review T2): every
 * refusal comes before the stack changes, the version preflight before the update, the update
 * before the build is published.
 */
class RunnerImageBuildSequenceTest {

    private static final String D = "baas-123456789012";
    private final FakeImageBuildAws aws = new FakeImageBuildAws();
    private final String bundledBase = new RunnerImageRenderer().definition().imageVersion();

    private RunnerImageBuild.Result build(Optional<RunnerImageExtension.WorkingCopy> pushed) throws Exception {
        return RunnerImageBuild.run(aws, D, "eu-central-1", pushed);
    }

    @Test
    void aBuildPreflightsThenUpdatesTheStackThenPublishes() throws Exception {
        var result = build(Optional.empty());

        assertThat(aws.calls).containsExactly(
            "stackExists", "stackParameters", "resolveParent", "preflightVersion " + bundledBase,
            "updateStack", "stackOutputs", "publish arn:pipeline /" + D + "/runner/ami-id");
        assertThat(result.amiId()).isEqualTo("ami-new");
        assertThat(aws.submitted).containsEntry(RunnerImageParameters.IMAGE_VERSION, bundledBase);
    }

    @Test
    void withoutAStackNothingIsChanged() {
        aws.stackExists = false;

        assertThatThrownBy(() -> build(Optional.empty())).hasMessageContaining("does not exist");
        assertThat(aws.calls).containsExactly("stackExists");
    }

    /** A CLI bundling an older base would replace the deployed component with it. */
    @Test
    void anOlderBundledBaseIsRefusedBeforeTheStackChanges() {
        aws.parameters.put(RunnerImageParameters.IMAGE_VERSION, "999.0.0");

        assertThatThrownBy(() -> build(Optional.empty())).hasMessageContaining("Upgrade the CLI first");
        assertThat(aws.calls).containsExactly("stackExists", "stackParameters");
    }

    /** A push based on a replaced extension would discard someone's extension; the stack holds the only copy. */
    @Test
    void aPushFromAStaleCopyIsRefusedBeforeTheStackChanges() {
        aws.parameters.put(RunnerImageParameters.EXTENSION_DATA, "name: theirs");
        var stale = RunnerImageExtension.parse("# baas-extension-base: 00000000\nname: mine\n");

        assertThatThrownBy(() -> build(Optional.of(stale))).hasMessageContaining("pushing would discard");
        assertThat(aws.calls).doesNotContain("updateStack").noneMatch(c -> c.startsWith("publish"));
    }

    /** A version Image Builder already holds would fail the update into a rollback. */
    @Test
    void aRefusedPreflightLeavesTheStackUnchanged() {
        aws.failPreflight = new IllegalStateException("Version taken");

        assertThatThrownBy(() -> build(Optional.empty())).hasMessage("Version taken");
        assertThat(aws.calls).doesNotContain("updateStack").noneMatch(c -> c.startsWith("publish"));
    }

    @Test
    void aStackWithoutThePipelineOutputPublishesNothing() {
        aws.outputs.clear();

        assertThatThrownBy(() -> build(Optional.empty())).hasMessageContaining("RunnerImagePipelineArn");
        assertThat(aws.calls).noneMatch(c -> c.startsWith("publish"));
    }
}
