package pl.wsztajerowski.baas.commands;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.wsztajerowski.baas.config.BaasConfig;

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

    /**
     * {@code --show-toplevel} returns the worktree directory, so a run launched from
     * {@code .claude/worktrees/ddb-phase3} was attributed to project {@code ddb-phase3} — a
     * partition {@code baas results} would never look in.
     */
    @Test
    void aLinkedWorktreeResolvesToItsRepository() {
        assertThat(GitProject.fromCommonDir("/home/dev/lynx-journal/.git")).isEqualTo("lynx-journal");
        assertThat(GitProject.fromCommonDir("/home/dev/lynx-journal/.git/")).isEqualTo("lynx-journal");
    }

    @Test
    void aBareRepositoryStillYieldsAName() {
        assertThat(GitProject.fromCommonDir("/srv/git/lynx-journal.git")).isEqualTo("lynx-journal");
    }

    @Test
    void anAbsentCommonDirYieldsNothingToDeriveFrom() {
        assertThat(GitProject.fromCommonDir(null)).isNull();
        assertThat(GitProject.fromCommonDir("  ")).isNull();
    }

    @Test
    void aBranchTagIsCallerOverridableRatherThanReserved() {
        assertThat(RunCommand.RESERVED_TAG_KEYS).doesNotContain("branch");
    }

    /**
     * Before the cutover, absent store configuration selected a no-op adapter: the run booted an
     * instance, measured, reported success and discarded every number. Failing here — before the
     * Maven build, the upload and the launch — is what replaced that, so the cost of a
     * misconfigured CLI is an error message rather than a paid instance and no data.
     */
    @Test
    void refusesToRunWhenTheResultsTableIsUnresolvable() {
        var config = configWithResultsTable(null);

        assertThatThrownBy(() -> RunCommand.resolveResultsTable(config, false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("baas config sync --core-stack-name baas-a1b2c3d4")
            .hasMessageContaining("--no-database");
    }

    @Test
    void treatsABlankResultsTableAsUnresolvable() {
        var config = configWithResultsTable("   ");

        assertThatThrownBy(() -> RunCommand.resolveResultsTable(config, false))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resolvesTheConfiguredResultsTable() {
        var config = configWithResultsTable("baas-a1b2c3d4-results");

        assertThat(RunCommand.resolveResultsTable(config, false))
            .contains("baas-a1b2c3d4-results");
    }

    /** Discarding results is legitimate, but it has to be asked for by name. */
    @Test
    void noDatabaseResolvesToNoTableWithoutConsultingTheConfig() {
        assertThat(RunCommand.resolveResultsTable(configWithResultsTable(null), true))
            .isEmpty();
    }

    private static BaasConfig configWithResultsTable(String table) {
        var config = new BaasConfig();
        config.getAws().setCoreStackName("baas-a1b2c3d4");
        config.getAws().setResultsTable(table);
        return config;
    }

    @Test
    void buildsTheDerivedTagsAlongsideCallerTags() {
        var command = new RunCommand();
        command.extraTags.put("experiment", "gc-tuning");

        var tags = command.buildRunnerTags("jmh", "lynx-journal", "abc123", "main");

        assertThat(tags)
            .containsEntry("project", "lynx-journal")
            .containsEntry("commit", "abc123")
            .containsEntry("branch", "main")
            .containsEntry("type", "jmh")
            .containsEntry("experiment", "gc-tuning");
    }

    @Test
    void anExplicitTagOverridesTheDerivedValue() {
        var command = new RunCommand();
        command.extraTags.put("project", "explicit");

        assertThat(command.buildRunnerTags("jmh", "derived", "abc123", "main"))
            .containsEntry("project", "explicit");
    }

    /**
     * Branch used to survive only as a path segment of the result path and was stored nowhere. The
     * unified prefix drops that segment, so what the path stopped carrying the tags must carry —
     * tags being the entire query surface `baas results` has.
     */
    @Test
    void tagsTheBranchNowThatThePathNoLongerCarriesIt() {
        var command = new RunCommand();

        assertThat(command.buildRunnerTags("jmh", "lynx-journal", "abc123", "main"))
            .containsEntry("branch", "main");
    }

    @Test
    void stillAllowsBranchToBeOverriddenByTheCaller() {
        var command = new RunCommand();
        command.extraTags.put("branch", "explicit");

        assertThat(command.buildRunnerTags("jmh", "lynx-journal", "abc123", "main"))
            .containsEntry("branch", "explicit");
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

        assertThatThrownBy(() -> command.buildRunnerTags("jmh", "lynx-journal", "abc123", "main"))
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

        assertThatThrownBy(() -> command.buildRunnerTags("jmh", "lynx-journal", "abc123", "main"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("type");
    }

    /** design.md deliberately specifies that the caller wins for project and commit. */
    @Test
    void stillAllowsCommitToBeOverriddenByTheCaller() {
        var command = new RunCommand();
        command.extraTags.put("commit", "deadbeef");

        assertThat(command.buildRunnerTags("jmh", "lynx-journal", "abc123", "main"))
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

    /**
     * A reactor build cannot name a release, so it cannot pin the runner JAR a run executes. The
     * refusal is the same no-fallback stance the runner AMI takes, and it lands before the Maven
     * build, before any upload and before the first AWS client is constructed — reachable in a
     * unit test precisely because nothing AWS-shaped happens first.
     */
    @Test
    void refusesToLaunchFromAnUnreleasedBuildWithoutARunnerJar() throws Exception {
        var command = new RunCommand();
        command.benchmarkType = "jmh";

        assertThat(command.call())
            .as("an unreleased CLI has no release to pin to, and there is no fallback")
            .isEqualTo(1);
    }

    @Test
    void theUnreleasedBuildIsWhatTriggersTheRefusalNotTheTypeCheck() {
        assertThat(pl.wsztajerowski.baas.BaasVersion.isReleased())
            .as("a reactor build always carries the placeholder version")
            .isFalse();
    }
}
