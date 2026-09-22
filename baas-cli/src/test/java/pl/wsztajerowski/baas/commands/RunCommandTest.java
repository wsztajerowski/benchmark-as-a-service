package pl.wsztajerowski.baas.commands;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.wsztajerowski.baas.config.BaasConfig;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
     * runner-image lookup, the upload and the launch — is what replaced that, so the cost of a
     * misconfigured CLI is an error message rather than a paid instance and no data.
     */
    @Test
    void refusesToRunWhenNoInstallationIsConfigured() {
        var config = configWithResultsTable(null);

        assertThatThrownBy(() -> RunCommand.resolveResultsTable(config, false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("baas config sync --name")
            .hasMessageContaining("--no-database");
    }

    @Test
    void treatsABlankInstallationAsUnconfigured() {
        var config = new BaasConfig();
        config.setPrefix("   ");

        assertThatThrownBy(() -> RunCommand.resolveResultsTable(config, false))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void derivesTheResultsTableFromTheConfiguredInstallation() {
        var config = configWithResultsTable("baas-123456789012-results");

        assertThat(RunCommand.resolveResultsTable(config, false))
            .contains("baas-123456789012-results");
    }

    /** Discarding results is legitimate, but it has to be asked for by name. */
    @Test
    void noDatabaseResolvesToNoTableWithoutConsultingTheConfig() {
        assertThat(RunCommand.resolveResultsTable(configWithResultsTable(null), true))
            .isEmpty();
    }

    /** The table is derived from the installation, so "no table" means "no installation". */
    private static BaasConfig configWithResultsTable(String table) {
        var config = new BaasConfig();
        if (table != null) {
            config.setPrefix(table.replaceAll("-results$", ""));
        }
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

    // ─── operator credentials warning ────────────────────────────────────────────

    /**
     * On a laptop with no operator profile the warning is the whole point: `run`/`results` are
     * meant to run under BaasCliOperatorRole, and falling through to `aws.profile` would silently
     * use deployer credentials.
     */
    @Test
    void warnsOnALaptopWithNoOperatorProfileAndNoAmbientCredentials() {
        assertThat(RunCommand.operatorCredentialsWarning(new BaasConfig(), Map.of()))
            .get().asString().contains("--operator-profile");
    }

    /**
     * In continuous integration the credentials come from an OIDC federation the job already
     * performed, so there is no profile to name and the warning's advice is wrong rather than
     * merely redundant — and a warning that is wrong where it always fires is one people learn to
     * ignore where it matters.
     */
    @Test
    void staysSilentWhenCredentialsComeFromTheEnvironment() {
        assertThat(RunCommand.operatorCredentialsWarning(new BaasConfig(),
            Map.of("AWS_ACCESS_KEY_ID", "ASIA...", "AWS_SESSION_TOKEN", "...")))
            .isEmpty();
        assertThat(RunCommand.operatorCredentialsWarning(new BaasConfig(),
            Map.of("AWS_WEB_IDENTITY_TOKEN_FILE", "/tmp/token", "AWS_ROLE_ARN", "arn:...")))
            .isEmpty();
    }

    @Test
    void staysSilentWhenAnOperatorProfileIsConfigured() {
        var config = new BaasConfig();
        config.getAws().setOperatorProfile("baas-operator");

        assertThat(RunCommand.operatorCredentialsWarning(config, Map.of())).isEmpty();
    }

    // ─── source: the trigger tag (2.1, 2.2) ─────────────────────────────────────
    //
    // Derived rather than left to convention, so that absence is meaningful: a key present only
    // when someone types it makes `--group-by source` unreliable in exactly the direction that
    // matters — verifying that CI and laptop runs are comparable.

    @Test
    void aLaptopRunIsTaggedLocal() {
        var command = new RunCommand();

        assertThat(command.buildRunnerTags("jmh", "lynx-journal", "abc123", "main", Map.of()))
            .containsEntry("source", "local");
    }

    @Test
    void aContinuousIntegrationRunIsTaggedCi() {
        var command = new RunCommand();

        assertThat(command.buildRunnerTags("jmh", "lynx-journal", "abc123", "main",
            Map.of("CI", "true", "GITHUB_ACTIONS", "true")))
            .containsEntry("source", "ci");
    }

    /**
     * Unlike a machine-observed key, `source` is caller-overridable — it says how a run was
     * triggered, which the instance never observes, so a supplied value cannot make a result's
     * tags disagree with its own environment.json. This is what lets a consumer label a nightly.
     */
    @Test
    void anExplicitSourceWinsOverTheDerivedOneRatherThanBeingRejected() {
        var command = new RunCommand();
        command.extraTags.put("source", "nightly");

        assertThat(command.buildRunnerTags("jmh", "lynx-journal", "abc123", "main",
            Map.of("CI", "true")))
            .containsEntry("source", "nightly");
    }

    @Test
    void sourceIsNotRejectedTheWayAMachineObservedKeyIs() {
        var command = new RunCommand();
        command.extraTags.put("source", "nightly");

        assertThatCode(() -> command.buildRunnerTags("jmh", "lynx-journal", "abc123", "main",
            Map.of()))
            .doesNotThrowAnyException();
    }

    /**
     * Some environments set {@code CI=false} to opt out; honouring that is what stops a developer
     * machine carrying a stray {@code CI} export from mislabelling every local run.
     */
    @Test
    void ciSetToFalseIsNotContinuousIntegration() {
        var command = new RunCommand();

        assertThat(command.buildRunnerTags("jmh", "lynx-journal", "abc123", "main",
            Map.of("CI", "false")))
            .containsEntry("source", "local");
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
     * `commit=unknown` is the same junk as the RESULT#unknown partition the runner now refuses:
     * a non-answer wearing a value's clothing, in the only query surface the tool has.
     */
    @Test
    void omitsAnUnresolvableCommitRatherThanRecordingUnknown() {
        var command = new RunCommand();

        assertThat(command.buildRunnerTags("jmh", "lynx-journal", null, "main"))
            .doesNotContainKey("commit")
            .containsEntry("branch", "main");
    }

    @Test
    void omitsAnUnresolvableBranchRatherThanRecordingUnknown() {
        var command = new RunCommand();

        assertThat(command.buildRunnerTags("jmh", "lynx-journal", "abc123", null))
            .doesNotContainKey("branch")
            .containsEntry("commit", "abc123");
    }

    @Test
    void aRunOutsideARepositoryStillCarriesItsProjectAndType() {
        var command = new RunCommand();

        assertThat(command.buildRunnerTags("jmh", "explicit-project", null, null))
            .containsEntry("project", "explicit-project")
            .containsEntry("type", "jmh")
            .doesNotContainKey("commit")
            .doesNotContainKey("branch");
    }

    /** --branch and --project had dedicated options; commit was overridable only via --tag. */
    @Test
    void acceptsADedicatedCommitOption() {
        var command = new RunCommand();

        new picocli.CommandLine(command)
            .parseArgs("--benchmark-jar", "b.jar", "--commit", "deadbeef", "jmh");

        assertThat(command.commit).isEqualTo("deadbeef");
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
     * The previous implementation merged git's stderr into the captured output and never checked
     * the exit code, so outside a git repository it returned the literal
     * {@code "fatal: not a git repository (or any of the parent directories): .git"} as if it were
     * a branch name, and {@code buildRunnerTags} then stored that text as the {@code branch} tag —
     * worse than the {@code "unknown"} placeholder this change removed. {@code currentGitBranch}
     * must now report absence, exactly like {@code currentGitCommit} already does via the same
     * exit-code-checked {@link #gitOutput(Path, String...)} seam.
     */
    @Test
    void aGitFailureYieldsNoBranchRatherThanTheErrorText(@TempDir Path notARepo) {
        var command = new RunCommand();

        assertThat(command.currentGitBranch(notARepo)).isNull();
    }

    /**
     * An explicit {@code --commit ""} or {@code --branch ""} is a value the caller supplied, so the
     * naive {@code field != null} check treats it as present and stores an empty-string tag — a
     * placeholder standing in for an unknown value, the same defect class as {@code "unknown"}.
     * {@code resolveProject} already blank-checks {@code --project}; {@code resolveBranch} and
     * {@code resolveCommit} must do the same and fall through to derivation, which here (outside a
     * git repository) yields absence rather than the empty string that was explicitly passed.
     */
    @Test
    void aBlankExplicitBranchIsTreatedAsAbsentRatherThanStoredEmpty(@TempDir Path notARepo) {
        var command = new RunCommand();
        command.branch = "";

        assertThat(command.resolveBranch(notARepo)).isNull();
    }

    @Test
    void aBlankExplicitCommitIsTreatedAsAbsentRatherThanStoredEmpty(@TempDir Path notARepo) {
        var command = new RunCommand();
        command.commit = "";

        assertThat(command.resolveCommit(notARepo)).isNull();
    }

    /**
     * A reactor build cannot name a release, so it cannot pin the runner JAR a run executes. The
     * refusal is the same no-fallback stance the runner AMI takes, and it lands before the project
     * or results table is resolved, before any upload and before the first AWS client is
     * constructed — reachable in a unit test precisely because nothing AWS-shaped happens first.
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

    /**
     * An installed `baas` is invoked from anywhere, so building whatever is in the working
     * directory stopped being coherent. The absence is pinned rather than merely untested: a
     * restored build would silently compile an unrelated project.
     */
    @Test
    void noLongerCarriesABuildStep() {
        assertThat(RunCommand.class.getDeclaredMethods())
            .extracting(java.lang.reflect.Method::getName)
            .doesNotContain("runMavenBuild");
        assertThat(RunCommand.class.getDeclaredFields())
            .extracting(java.lang.reflect.Field::getName)
            .doesNotContain("skipBuild");
    }

    /**
     * With no build and no config default, an unnamed JAR has nowhere to come from. picocli
     * rejects it at parse time, which is before the AMI lookup and before any upload.
     */
    @Test
    void refusesToRunWithoutAnExplicitBenchmarkJar() {
        var parser = new picocli.CommandLine(new RunCommand());

        assertThatThrownBy(() -> parser.parseArgs("jmh"))
            .isInstanceOf(picocli.CommandLine.MissingParameterException.class)
            .hasMessageContaining("--benchmark-jar");
    }

    @Test
    void acceptsAnExplicitBenchmarkJar() {
        var command = new RunCommand();

        new picocli.CommandLine(command).parseArgs("--benchmark-jar", "target/b.jar", "jmh");

        assertThat(command.benchmarkJar).isEqualTo(Path.of("target/b.jar"));
    }

    /**
     * The default pointed at jmh-benchmarks/target/jmh-benchmarks.jar — a path from an older
     * layout, filed as part of A6. Harmless while the build usually produced something; the only
     * fallback once the build is gone.
     */
    @Test
    void theBenchmarkConfigSectionIsGoneEntirely() {
        assertThat(BaasConfig.class.getDeclaredClasses())
            .extracting(Class::getSimpleName)
            .doesNotContain("BenchmarkConfig");
    }
}
