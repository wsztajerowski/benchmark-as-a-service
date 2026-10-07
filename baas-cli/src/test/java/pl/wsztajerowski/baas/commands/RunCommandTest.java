package pl.wsztajerowski.baas.commands;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.wsztajerowski.baas.config.BaasConfig;

import java.nio.file.Path;
import java.util.List;
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
     * {@code --show-toplevel} returns the worktree directory, so a job launched from
     * {@code .claude/worktrees/ddb-phase3} was attributed to project {@code ddb-phase3} — a
     * partition {@code baas results query} would never look in.
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

    /**
     * The project lands unescaped in an S3 prefix and a DynamoDB partition, and on a user-data
     * line. The allowed set is GitHub's own, so any repository's name is accepted as it stands.
     */
    @Test
    void acceptsEveryCharacterAGitHubRepositoryNameMayHold() {
        for (String name : List.of("benchmark-as-a-service", "lynx_journal", "node.js", ".github",
            "Repo-2.0_rc", "a")) {
            assertThat(RunCommand.requireValidProject(name, "--project")).isEqualTo(name);
        }
    }

    @Test
    void refusesAProjectNameNoGitHubRepositoryCouldHave() {
        for (String name : List.of("it's", "my bench", "x$(id)", "owner/repo", "a;b", "zażółć")) {
            assertThatThrownBy(() -> RunCommand.requireValidProject(name, "--project"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'" + name + "'")
                .hasMessageContaining("--project");
        }
    }

    @Test
    void anExplicitProjectIsCheckedBeforeAnythingElseRuns() {
        var command = new RunCommand();
        command.project = "it's";

        assertThatThrownBy(() -> command.resolveProject(new BaasConfig()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("--project");
    }

    /** A clone into a directory GitHub would not name is the derived case; the way out is a flag. */
    @Test
    void aDerivedNameThatFailsTheCheckPointsAtTheFlag() {
        assertThatThrownBy(() -> RunCommand.requireValidProject("my bench", "The benchmark JAR's repository directory"))
            .hasMessageContaining("Pass --project <name>.");
    }

    @Test
    void aBranchTagIsCallerOverridableRatherThanReserved() {
        assertThat(RunCommand.RESERVED_TAG_KEYS).doesNotContain("branch");
    }

    /**
     * Before the cutover, absent store configuration selected a no-op adapter: the job booted an
     * instance, measured, reported success and discarded every number. Failing here — before the
     * runner-image lookup, the upload and the launch — is what replaced that, so the cost of a
     * misconfigured CLI is an error message rather than a paid instance and no data.
     */
    @Test
    void refusesToRunWhenNoDeploymentIsConfigured() {
        var config = configWithResultsTable(null);

        assertThatThrownBy(() -> RunCommand.resolveResultsTable(config))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("baas config sync --deployment")
            .hasMessageNotContaining("--no-database");
    }

    @Test
    void treatsABlankDeploymentAsUnconfigured() {
        var config = new BaasConfig();
        config.setPrefix("   ");

        assertThatThrownBy(() -> RunCommand.resolveResultsTable(config))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void derivesTheResultsTableFromTheConfiguredDeployment() {
        var config = configWithResultsTable("baas-123456789012-results");

        assertThat(RunCommand.resolveResultsTable(config))
            .isEqualTo("baas-123456789012-results");
    }

    /** The table is derived from the deployment, so "no table" means "no deployment". */
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
        command.extraTags.put("commit", "abc123");
        command.extraTags.put("branch", "main");

        var tags = command.buildRunnerTags("jmh", "lynx-journal");

        assertThat(tags)
            .containsEntry("project", "lynx-journal")
            .containsEntry("commit", "abc123")
            .containsEntry("branch", "main")
            .containsEntry("type", "jmh")
            .containsEntry("experiment", "gc-tuning");
    }

    /**
     * The runner reads the project from this tag, while the S3 prefix and the job item take
     * --project: a caller value stored the measurements under one project and the job under another.
     */
    @Test
    void aProjectTagIsRejectedInFavourOfTheProjectOption() {
        var command = new RunCommand();
        command.extraTags.put("project", "explicit");

        assertThatThrownBy(() -> command.buildRunnerTags("jmh", "derived"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("--project");
    }

    @Test
    void aProjectTagIsRejectedEvenWhenItAgreesWithTheProject() {
        var command = new RunCommand();
        command.extraTags.put("project", "same");

        assertThatThrownBy(() -> command.buildRunnerTags("jmh", "same"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Branch used to survive only as a path segment of the result path and was stored nowhere. The
     * unified prefix drops that segment, so what the path stopped carrying the tags must carry —
     * tags being the entire query surface `baas results query` has.
     */
    @Test
    void tagsTheBranchNowThatThePathNoLongerCarriesIt() {
        var command = new RunCommand();
        command.extraTags.put("branch", "main");

        assertThat(command.buildRunnerTags("jmh", "lynx-journal"))
            .containsEntry("branch", "main");
    }

    // ─── operator credentials warning ────────────────────────────────────────────

    /**
     * On a laptop with no operator profile the warning is the whole point: `run`/`results` are
     * meant to run under BaasCliOperatorRole, and falling through to `aws.deployerProfile` would silently
     * use deployer credentials.
     */
    @Test
    void warnsOnALaptopWithNoOperatorProfileAndNoAmbientCredentials() {
        assertThat(RunCommand.operatorCredentialsWarning(new BaasConfig(), Map.of()))
            .get().asString().contains("--operator-aws-profile");
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
    // when someone types it makes `--best-per source` unreliable in exactly the direction that
    // matters — verifying that CI and laptop runs are comparable.

    @Test
    void aLaptopJobIsTaggedLocal() {
        var command = new RunCommand();

        assertThat(command.buildRunnerTags("jmh", "lynx-journal", Map.of()))
            .containsEntry("source", "local");
    }

    @Test
    void aContinuousIntegrationJobIsTaggedCi() {
        var command = new RunCommand();

        assertThat(command.buildRunnerTags("jmh", "lynx-journal",
            Map.of("CI", "true", "GITHUB_ACTIONS", "true")))
            .containsEntry("source", "ci");
    }

    /**
     * Unlike a machine-observed key, `source` is caller-overridable — it says how a job was
     * triggered, which the instance never observes, so a supplied value cannot make a result's
     * tags disagree with its own environment.json. This is what lets a consumer label a nightly.
     */
    @Test
    void anExplicitSourceWinsOverTheDerivedOneRatherThanBeingRejected() {
        var command = new RunCommand();
        command.extraTags.put("source", "nightly");

        assertThat(command.buildRunnerTags("jmh", "lynx-journal",
            Map.of("CI", "true")))
            .containsEntry("source", "nightly");
    }

    @Test
    void sourceIsNotRejectedTheWayAMachineObservedKeyIs() {
        var command = new RunCommand();
        command.extraTags.put("source", "nightly");

        assertThatCode(() -> command.buildRunnerTags("jmh", "lynx-journal",
            Map.of()))
            .doesNotThrowAnyException();
    }

    /**
     * Some environments set {@code CI=false} to opt out; honouring that is what stops a developer
     * machine carrying a stray {@code CI} export from mislabelling every local job.
     */
    @Test
    void ciSetToFalseIsNotContinuousIntegration() {
        var command = new RunCommand();

        assertThat(command.buildRunnerTags("jmh", "lynx-journal",
            Map.of("CI", "false")))
            .containsEntry("source", "local");
    }

    @Test
    void stillAllowsBranchToBeOverriddenByTheCaller() {
        var command = new RunCommand();
        command.extraTags.put("branch", "explicit");

        assertThat(command.buildRunnerTags("jmh", "lynx-journal"))
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

        assertThatThrownBy(() -> command.buildRunnerTags("jmh", "lynx-journal"))
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
     * An image extension may swap Corretto for another vendor's build, so the vendor is observed on
     * the instance like the version is — and a caller value would let a result claim a JVM it did
     * not run on.
     */
    @Test
    void rejectsACallerTagForTheJvmVendor() {
        var command = new RunCommand();
        command.extraTags.put("jvmVendor", "Acme");

        assertThatThrownBy(() -> command.buildRunnerTags("jmh", "lynx-journal"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("jvmVendor");
    }

    /**
     * type is derived from the executed subcommand — overriding it would make a JMH job report
     * type=jcstress while the manifest and the actual subcommand disagree, the same defect class
     * as the other five reserved keys.
     */
    @Test
    void rejectsACallerTagThatCollidesWithTheDerivedTypeKey() {
        var command = new RunCommand();
        command.extraTags.put("type", "jcstress");

        assertThatThrownBy(() -> command.buildRunnerTags("jmh", "lynx-journal"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("type");
    }

    /** The caller supplies commit; it is never derived. */
    @Test
    void stillAllowsCommitToBeOverriddenByTheCaller() {
        var command = new RunCommand();
        command.extraTags.put("commit", "deadbeef");

        assertThat(command.buildRunnerTags("jmh", "lynx-journal"))
            .containsEntry("commit", "deadbeef");
    }

    /**
     * `commit=unknown` is the same junk as the RESULT#unknown partition the runner now refuses:
     * a non-answer wearing a value's clothing, in the only query surface the tool has. Nothing is
     * derived any more, so an untagged run simply carries neither key — even inside a repository.
     */
    @Test
    void neitherCommitNorBranchIsDerivedWhenTheCallerSuppliesNone() {
        var command = new RunCommand();

        assertThat(command.buildRunnerTags("jmh", "lynx-journal"))
            .doesNotContainKey("commit")
            .doesNotContainKey("branch");
    }

    @Test
    void aJobOutsideARepositoryStillCarriesItsProjectAndType() {
        var command = new RunCommand();

        assertThat(command.buildRunnerTags("jmh", "explicit-project"))
            .containsEntry("project", "explicit-project")
            .containsEntry("type", "jmh")
            .doesNotContainKey("commit")
            .doesNotContainKey("branch");
    }

    /**
     * --branch and --commit duplicated --tag once git stopped supplying them; --ami-id could only name
     * an image outside the one-image invariant; --max-wall-clock could be set below --timeout.
     */
    @Test
    void theRemovedOptionsAreUnknown() {
        for (String option : new String[]{"--branch", "--commit", "--ami-id", "--max-wall-clock"}) {
            var parser = new picocli.CommandLine(new RunCommand());

            assertThatThrownBy(() -> parser.parseArgs("--benchmark-jar", "b.jar", option, "x", "jmh"))
                .as(option)
                .isInstanceOf(picocli.CommandLine.UnmatchedArgumentException.class);
        }
    }

    /**
     * Every job records its status in the results table, so there is no job without one; the
     * option that discarded measurements is gone rather than kept as a no-op.
     */
    @Test
    void theDiscardOptionIsUnknown() {
        var parser = new picocli.CommandLine(new RunCommand());

        assertThatThrownBy(() -> parser.parseArgs("--benchmark-jar", "b.jar", "--no-database", "jmh"))
            .isInstanceOf(picocli.CommandLine.UnmatchedArgumentException.class);
    }

    /** Exit 2, the usage-error code, before the unreleased-build check that would exit 1. */
    @Test
    void anUnknownFormatIsRefusedRatherThanReadAsText() {
        int exit = new picocli.CommandLine(new RunCommand())
            .execute("--benchmark-jar", "b.jar", "--format", "jsno", "jmh");

        assertThat(exit).isEqualTo(2);
    }

    // ─── project resolution ──────────────────────────────────────────────────────

    @Test
    void theProjectIsRequiredWhenGitDerivationIsOff() {
        var command = new RunCommand();
        command.benchmarkJar = Path.of("target/b.jar");

        assertThatThrownBy(() -> command.resolveProject(new BaasConfig()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("--project")
            .hasMessageContaining("--git-resolve-project");
    }

    @Test
    void anExplicitProjectWinsWithoutConsultingGit(@TempDir Path notARepo) {
        var command = new RunCommand();
        command.project = "explicit";
        command.benchmarkJar = notARepo.resolve("b.jar");

        assertThat(command.resolveProject(gitDerivation(true))).isEqualTo("explicit");
    }

    /**
     * The JAR is what is measured; the shell's directory is incidental. The test runs from inside
     * this repository, so a working-directory lookup would answer this repository's name instead.
     */
    @Test
    void derivesTheProjectFromTheRepositoryHoldingTheJarNotTheWorkingDirectory(@TempDir Path parent)
        throws Exception {
        Path repo = parent.resolve("lynx-journal");
        Path target = repo.resolve("target");
        java.nio.file.Files.createDirectories(target);
        new ProcessBuilder("git", "init", "-q").directory(repo.toFile()).start().waitFor();
        var command = new RunCommand();
        command.benchmarkJar = target.resolve("b.jar");

        assertThat(command.resolveProject(gitDerivation(true))).isEqualTo("lynx-journal");
    }

    /**
     * call() itself can't run in a unit test — it needs AWS credentials and a published runner
     * image — so this pins the reachable piece: resolveProject() genuinely throws (not a mock
     * standing in for one) for a JAR outside any repository. {@code notARepo} is a fresh JUnit
     * @TempDir, which is never inside a git working tree.
     */
    @Test
    void aJarOutsideAnyRepositoryFailsNamingTheProjectOption(@TempDir Path notARepo) {
        var command = new RunCommand();
        command.benchmarkJar = notARepo.resolve("b.jar");

        assertThatThrownBy(() -> command.resolveProject(gitDerivation(true)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("--project");
    }

    private static BaasConfig gitDerivation(boolean enabled) {
        var config = new BaasConfig();
        config.getGit().setResolveProject(enabled);
        return config;
    }

    // ─── watchdog bound ──────────────────────────────────────────────────────────

    @Test
    void theDefaultWatchdogBoundIsUnchanged() {
        assertThat(RunCommand.watchdogBound(7200, BaasConfig.DEFAULT_WATCHDOG_MARGIN_SECONDS))
            .isEqualTo(7500);
    }

    @Test
    void theMarginIsAddedToTheTimeout() {
        assertThat(RunCommand.watchdogBound(1000, 120)).isEqualTo(1120);
    }

    /** Below the floor the watchdog could kill the instance before its final status is written. */
    @Test
    void aMarginBelowTheFloorIsRefused() {
        assertThatThrownBy(() -> RunCommand.watchdogBound(7200, 10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("60");
    }

    /**
     * A reactor build cannot name a release, so it cannot pin the runner JAR a job executes. The
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

    // ─── timings: flags over configuration, bound always derived ───────────────────

    @Test
    void theConfiguredMarginAppliesWhenNoFlagIsGiven() {
        var config = new BaasConfig();
        config.getEc2().setBenchmarkTimeoutSeconds(1000);
        config.getEc2().setWatchdogMarginSeconds(120);

        assertThat(new RunCommand().resolveTimings(config))
            .isEqualTo(new RunCommand.Timings(1000, 1120));
    }

    /**
     * The case the absolute wall clock got wrong: a raised timeout with the margin left at its
     * default still ends up with the watchdog after the benchmark's own timeout.
     */
    @Test
    void aRaisedConfiguredTimeoutCarriesTheWatchdogWithIt() {
        var config = new BaasConfig();
        config.getEc2().setBenchmarkTimeoutSeconds(10_000);

        assertThat(new RunCommand().resolveTimings(config).watchdogSeconds()).isEqualTo(10_300);
    }

    @Test
    void flagsWinOverConfiguration() {
        var config = new BaasConfig();
        config.getEc2().setWatchdogMarginSeconds(900);
        var command = new RunCommand();
        command.timeoutSeconds = 600;
        command.watchdogMarginSeconds = 60;

        assertThat(command.resolveTimings(config)).isEqualTo(new RunCommand.Timings(600, 660));
    }

    /** A hand-edited config below the floor is refused like the flag is. */
    @Test
    void aConfiguredMarginBelowTheFloorIsRefused() {
        var config = new BaasConfig();
        config.getEc2().setWatchdogMarginSeconds(5);

        assertThatThrownBy(() -> new RunCommand().resolveTimings(config))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
