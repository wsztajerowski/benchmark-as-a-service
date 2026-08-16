package pl.wsztajerowski.baas.commands;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunCommandTest {

    @Test
    void derivesProjectFromTheRepositoryDirectoryName() {
        assertThat(RunCommand.projectFromToplevel("/Users/dev/workspace/lynx-journal"))
            .isEqualTo("lynx-journal");
    }

    @Test
    void stripsATrailingSeparatorFromTheToplevel() {
        assertThat(RunCommand.projectFromToplevel("/Users/dev/workspace/lynx-journal/"))
            .isEqualTo("lynx-journal");
    }

    @Test
    void rejectsAnEmptyToplevel() {
        assertThat(RunCommand.projectFromToplevel("")).isNull();
    }

    @Test
    void buildsTheDerivedTagsAlongsideCallerTags() {
        var command = new RunCommand();
        command.extraTags.put("experiment", "gc-tuning");

        var tags = command.buildRunnerTags("jmh", "lynx-journal", "abc123");

        assertThat(tags)
            .containsEntry("project", "lynx-journal")
            .containsEntry("commit", "abc123")
            .containsEntry("type", "jmh")
            .containsEntry("experiment", "gc-tuning");
    }

    @Test
    void anExplicitTagOverridesTheDerivedValue() {
        var command = new RunCommand();
        command.extraTags.put("project", "explicit");

        assertThat(command.buildRunnerTags("jmh", "derived", "abc123"))
            .containsEntry("project", "explicit");
    }

    @Test
    void doesNotTagBranchAutomatically() {
        var command = new RunCommand();

        assertThat(command.buildRunnerTags("jmh", "lynx-journal", "abc123"))
            .as("branch is a custom user tag per design.md — do not wire --branch into it")
            .doesNotContainKey("branch");
    }

    /**
     * imageVersion/instanceType/jdk/cpuModel/cpuArch are captured on the instance so a result's
     * tags can never disagree with its own environment.json (see UserDataScriptBuilder). Silently
     * dropping a colliding --tag would be its own surprise, so buildRunnerTags rejects it outright
     * instead — same defect class as the one this branch's final review flagged.
     */
    @Test
    void rejectsACallerTagThatCollidesWithAMachineObservedKey() {
        var command = new RunCommand();
        command.extraTags.put("jdk", "8");

        assertThatThrownBy(() -> command.buildRunnerTags("jmh", "lynx-journal", "abc123"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("jdk")
            .as("the error must list every reserved key, not just the one that collided")
            .hasMessageContaining("imageVersion")
            .hasMessageContaining("instanceType")
            .hasMessageContaining("cpuModel")
            .hasMessageContaining("cpuArch")
            .hasMessageContaining("type");
    }

    /**
     * type is derived from the executed subcommand — overriding it would make a JMH run report
     * type=jcstress while the manifest and the actual subcommand disagree, the same defect class
     * as the other five reserved keys.
     */
    @Test
    void rejectsACallerTagThatCollidesWithTheDerivedTypeKey() {
        var command = new RunCommand();
        command.extraTags.put("type", "jcstress");

        assertThatThrownBy(() -> command.buildRunnerTags("jmh", "lynx-journal", "abc123"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("type");
    }

    /** design.md deliberately specifies that the caller wins for project and commit. */
    @Test
    void stillAllowsCommitToBeOverriddenByTheCaller() {
        var command = new RunCommand();
        command.extraTags.put("commit", "deadbeef");

        assertThat(command.buildRunnerTags("jmh", "lynx-journal", "abc123"))
            .containsEntry("commit", "deadbeef");
    }

    /**
     * call() itself can't run in a unit test — it needs a real BaasConfig, AWS credentials, and
     * a published runner image — so this pins the one piece that IS reachable without any of
     * that: resolveProject() genuinely throws (not a mock standing in for one) when run outside
     * a git repository and no --project was given. {@code notARepo} is a fresh JUnit @TempDir,
     * which is never inside a git working tree.
     */
    @Test
    void resolveProjectThrowsOutsideAGitRepository(@TempDir Path notARepo) {
        var command = new RunCommand();

        assertThatThrownBy(() -> command.resolveProject(notARepo))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("--project");
    }
}
