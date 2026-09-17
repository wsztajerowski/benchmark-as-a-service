# Installable CLI Command — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `baas` an installable command that reports a real released version, and remove
`baas run`'s Maven build so an installed CLI never builds the directory it happens to be standing in.

**Architecture:** A POSIX `install.sh`, published as a release asset with its version baked in at
release time, fetches a checksum-verified `baas-cli.jar` into `~/.local/share/baas/` and writes a
launcher shim to `~/.local/bin/baas`. `baas run` loses `runMavenBuild()`, requires `--benchmark-jar`,
and omits `commit`/`branch` tags rather than storing `"unknown"`. A CI job builds a fixture release
and installs from it, so the installer is exercised on macOS and Linux on every PR.

**Tech Stack:** Java 25 + picocli + JUnit 6 + AssertJ (CLI); POSIX `sh` (installer); GitHub Actions +
semantic-release (publishing).

**Spec:** `openspec/changes/installable-cli-command/` — `specs/**/spec.md` (requirements),
`design.md` (technical decisions), `tasks.md` (checklist this plan decomposes).

## Global Constraints

- `pom.xml` version stays `0.0.0-semantically-released`. Never bump it by hand.
- `scripts/install.sh` is **POSIX `sh`**, not bash. No arrays, no `[[ ]]`, no `local` beyond one word,
  no `sed -i` (BSD `sed` differs), no `sort -V`, no `readlink -f`, no `mktemp -d --tmpdir`.
- The installer must work on stock macOS, which has `shasum -a 256` but **no `sha256sum`**.
- Source repository default: `wsztajerowski/benchmark-as-a-service`. Public, so all fetches are
  anonymous.
- Placeholder version constant, shared by `pom.xml`, `BaasVersion.PLACEHOLDER` and the installer:
  `0.0.0-semantically-released`.
- Install layout: `~/.local/bin/baas`, `~/.local/share/baas/baas-cli.jar`,
  `~/.local/share/baas/install.sh`. `~/.baas/` belongs to `baas admin setup` and is never read,
  written or deleted by the installer.
- Run the **full reactor** (`mvn clean verify`) before trusting a green build; `-pl` alone can fail on
  missing shaded fixture JARs.
- Java version floor for the shim's check: major `25`.

## File Structure

**Modified**
- `baas-cli/src/main/java/pl/wsztajerowski/baas/commands/RunCommand.java` — remove the build, require
  the JAR, add `--commit`, omit unresolved tags.
- `baas-cli/src/main/java/pl/wsztajerowski/baas/config/BaasConfig.java` — delete
  `BenchmarkConfig.jarPath`.
- `baas-cli/src/main/java/pl/wsztajerowski/baas/commands/ConfigShowSubcommand.java` — drop the
  `jarPath` line.
- `baas-cli/src/test/java/pl/wsztajerowski/baas/commands/RunCommandTest.java` — tests for all of it.
- `.github/workflows/release.yml` — checksum, version bake, two new assets.
- `CLAUDE.md`, `README.md`, `docs/adr/0001-self-contained-baas-cli.md`,
  `docs/review/baas-cli-findings.md`.

**Created**
- `scripts/install.sh` — the installer. One file: version resolution, fetch, verify, install, shim,
  prerequisites, update, uninstall.
- `scripts/tests/install-test.sh` — a POSIX test harness for the installer. The repo has no shell
  test framework, and adding one for a single script is not worth it; this is ~20 lines of assert
  helpers plus cases, runnable locally and from CI against a local fixture server.
- `.github/workflows/install-test.yml` — matrix job that builds a fixture release and runs the above.

---

## Task 1: `baas run` stops building the benchmark project

**Files:**
- Modify: `baas-cli/src/main/java/pl/wsztajerowski/baas/commands/RunCommand.java:213-216, 408-415, 3-5 (imports)`
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/commands/RunCommandTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `RunCommand` with no `runMavenBuild()` method and no `skipBuild` field. Task 2 relies on
  both being gone.

- [ ] **Step 1: Write the failing test**

Add to `RunCommandTest.java`:

```java
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
```

- [ ] **Step 2: Run it and watch it fail**

Run: `mvn -q -pl baas-cli test -Dtest=RunCommandTest#noLongerCarriesABuildStep`
Expected: FAIL — both `runMavenBuild` and `skipBuild` are still present.

- [ ] **Step 3: Delete the build**

In `RunCommand.java`, delete the `--skip-build` option field:

```java
    @Option(names = "--skip-build", description = "Skip mvn build step.")
    boolean skipBuild;
```

Delete the call site (currently `// 2. Build`):

```java
        // 2. Build
        if (!skipBuild) {
            runMavenBuild();
        }
```

Delete the method:

```java
    private void runMavenBuild() throws IOException, InterruptedException {
        logger.info("Building benchmark JAR (mvn clean package -q)...");
        var pb = new ProcessBuilder("mvn", "clean", "package", "-q", "-DskipTests")
            .inheritIO()
            .directory(Path.of(".").toAbsolutePath().normalize().toFile());
        int exit = pb.start().waitFor();
        if (exit != 0) throw new RuntimeException("Maven build failed with exit code " + exit);
    }
```

- [ ] **Step 4: Fix the command description**

The `@Command` annotation still says the command builds. Change:

```java
    description = "Build a benchmark JAR, launch an EC2 runner, and poll for results.",
```

to:

```java
    description = "Launch an EC2 runner for a pre-built benchmark JAR, and poll for results.",
```

- [ ] **Step 5: Run the test and the full class**

Run: `mvn -q -pl baas-cli test -Dtest=RunCommandTest`
Expected: the new test PASSES. Other tests may fail to compile if they referenced `skipBuild` —
fix those references now.

- [ ] **Step 6: Commit**

```bash
git add baas-cli/src/main/java/pl/wsztajerowski/baas/commands/RunCommand.java \
        baas-cli/src/test/java/pl/wsztajerowski/baas/commands/RunCommandTest.java
git commit -m "feat(cli)!: baas run no longer builds the benchmark project"
```

---

## Task 2: `--benchmark-jar` is required and the config fallback is deleted

**Files:**
- Modify: `baas-cli/src/main/java/pl/wsztajerowski/baas/commands/RunCommand.java:75-76, 218-223`
- Modify: `baas-cli/src/main/java/pl/wsztajerowski/baas/config/BaasConfig.java:115-124`
- Modify: `baas-cli/src/main/java/pl/wsztajerowski/baas/commands/ConfigShowSubcommand.java:60`
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/commands/RunCommandTest.java`

**Interfaces:**
- Consumes: Task 1's `RunCommand` with no build step.
- Produces: `RunCommand.benchmarkJar` as a required picocli option;
  `BaasConfig.BenchmarkConfig` with only `asyncProfilerVersion`.

- [ ] **Step 1: Write the failing tests**

```java
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
    void theBenchmarkConfigNoLongerCarriesAJarPath() {
        assertThat(BaasConfig.BenchmarkConfig.class.getDeclaredMethods())
            .extracting(java.lang.reflect.Method::getName)
            .doesNotContain("getJarPath", "setJarPath");
    }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `mvn -q -pl baas-cli test -Dtest=RunCommandTest`
Expected: FAIL — `parseArgs("jmh")` succeeds today, and `getJarPath` still exists.

- [ ] **Step 3: Make the option required**

```java
    @Option(names = "--benchmark-jar", required = true,
        description = "Path to the pre-built benchmark JAR. Required — baas run builds nothing.")
    Path benchmarkJar;
```

- [ ] **Step 4: Delete the config fallback and fix the error message**

Replace the JAR resolution block:

```java
        // 3. Determine JAR path
        Path jarPath = benchmarkJar != null ? benchmarkJar : Path.of(config.getBenchmark().getJarPath());
        if (!jarPath.toFile().exists()) {
            logger.error("Benchmark JAR not found: {}\nRun without --skip-build or specify --benchmark-jar.", jarPath);
            return 1;
        }
```

with:

```java
        // 3. The JAR is named, never derived. Checked before the image lookup and before any
        //    upload, like every other precondition this command has.
        if (!benchmarkJar.toFile().exists()) {
            logger.error("Benchmark JAR not found: {}\nBuild it first, then pass --benchmark-jar.",
                benchmarkJar);
            return 1;
        }
        Path jarPath = benchmarkJar;
```

- [ ] **Step 5: Move the check above the image lookup**

The image resolution block (`// 1.` … `runnerImage.amiId()`, around `RunCommand:205-211`) currently
runs first. Move the JAR existence check above it so a missing JAR fails without an AWS call.

- [ ] **Step 6: Delete `jarPath` from config**

In `BaasConfig.java`, delete from `BenchmarkConfig`:

```java
        private String jarPath = "jmh-benchmarks/target/jmh-benchmarks.jar";

        public String getJarPath() { return jarPath; }
        public void setJarPath(String p) { this.jarPath = p; }
```

In `ConfigShowSubcommand.java:60`, delete:

```java
            .append("  jarPath:                  ").append(config.getBenchmark().getJarPath()).append('\n');
```

Make sure the preceding line still terminates the chain with `;`.

- [ ] **Step 7: Run the full reactor**

Run: `mvn clean verify`
Expected: PASS. A stale `jarPath:` assertion in a `ConfigShowSubcommand` test is the likely
failure — update it to expect the line's absence.

- [ ] **Step 8: Commit**

```bash
git add baas-cli/src/main/java/pl/wsztajerowski/baas/commands/RunCommand.java \
        baas-cli/src/main/java/pl/wsztajerowski/baas/config/BaasConfig.java \
        baas-cli/src/main/java/pl/wsztajerowski/baas/commands/ConfigShowSubcommand.java \
        baas-cli/src/test/java/pl/wsztajerowski/baas/commands/RunCommandTest.java
git commit -m "feat(cli)!: require --benchmark-jar and delete the benchmark.jarPath config key"
```

---

## Task 3: `commit` and `branch` are omitted rather than invented

**Files:**
- Modify: `baas-cli/src/main/java/pl/wsztajerowski/baas/commands/RunCommand.java:100-103, 177, 417-428, 460-470, 515-532`
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/commands/RunCommandTest.java`

**Interfaces:**
- Consumes: Task 2's `RunCommand`.
- Produces: `buildRunnerTags(String benchmarkType, String project, String commit, String branch)` —
  same signature, but `commit` and `branch` are now nullable and a null omits the key.
  `currentGitCommit()` and `currentGitBranch()` return `null` rather than `"unknown"`.
  New field `String commit` bound to `--commit`.

- [ ] **Step 1: Write the failing tests**

```java
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
```

- [ ] **Step 2: Run them and watch them fail**

Run: `mvn -q -pl baas-cli test -Dtest=RunCommandTest`
Expected: FAIL — nulls are currently put into the map, and there is no `commit` field.

- [ ] **Step 3: Add the `--commit` option**

Beside the existing `--branch` option (`RunCommand:100`):

```java
    @Option(names = "--commit",
        description = "Commit recorded as the run's commit tag (defaults to the current git commit).")
    String commit;
```

- [ ] **Step 4: Omit null tags**

In `buildRunnerTags`, replace the three unconditional puts:

```java
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put(TagKeys.PROJECT, project);
        tags.put(TagKeys.COMMIT, commit);
        tags.put(TagKeys.BRANCH, branch);
        tags.put(TagKeys.TYPE, benchmarkType);
        tags.putAll(extraTags);
        return tags;
```

with:

```java
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put(TagKeys.PROJECT, project);
        // An unresolvable commit or branch is absent, not "unknown". A placeholder value is
        // indistinguishable from a real one at query time, which is how RESULT#unknown grew.
        if (commit != null) {
            tags.put(TagKeys.COMMIT, commit);
        }
        if (branch != null) {
            tags.put(TagKeys.BRANCH, branch);
        }
        tags.put(TagKeys.TYPE, benchmarkType);
        tags.putAll(extraTags);
        return tags;
```

- [ ] **Step 5: Stop inventing values in the git helpers**

```java
    private String currentGitBranch() {
        try {
            var pb = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                .redirectErrorStream(true);
            var proc = pb.start();
            String out = new String(proc.getInputStream().readAllBytes()).trim();
            proc.waitFor();
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }
```

and the same shape for `currentGitCommit()` — return `commit` when non-null and non-blank, else
`null`, replacing `return commit != null ? commit : "unknown";`.

- [ ] **Step 6: Wire `--commit` into resolution**

Beside `String resolvedBranch = branch != null ? branch : currentGitBranch();` (`RunCommand:177`),
add:

```java
        String resolvedCommit = commit != null ? commit : currentGitCommit();
```

and pass `resolvedCommit` to `buildRunnerTags` where `currentGitCommit()` is called today
(`RunCommand:265`).

- [ ] **Step 7: Correct the two stale comments**

`RunCommand:477` and the message text at `RunCommand:524` both claim `--commit` already exists as an
override. It does now — verify the wording still reads correctly and mentions `--branch` too.

- [ ] **Step 8: Run the full reactor**

Run: `mvn clean verify`
Expected: PASS.

- [ ] **Step 9: Confirm `benchmark-runner` needs no change**

Run: `grep -rn "commit\|branch" benchmark-runner/src/main --include='*.java'`
Expected: no hits. The runner records whatever `--tag` pairs it is handed and injects neither key,
so `results-store-schema`'s modified requirement — record them when supplied, omit them otherwise —
is satisfied entirely by this task's omission upstream. Confirm rather than assume; if a hit
appears, the runner injects a default and this task is incomplete.

- [ ] **Step 10: Commit**

```bash
git add baas-cli/src/main/java/pl/wsztajerowski/baas/commands/RunCommand.java \
        baas-cli/src/test/java/pl/wsztajerowski/baas/commands/RunCommandTest.java
git commit -m "feat(cli)!: omit unresolvable commit and branch tags, add --commit"
```

---

## Task 4: The installer's skeleton, version resolution and test harness

**Files:**
- Create: `scripts/install.sh`
- Create: `scripts/tests/install-test.sh`

**Interfaces:**
- Produces: `scripts/install.sh` with `BAAS_VERSION_DEFAULT`, `PLACEHOLDER`, `BAAS_REPO`,
  `BAAS_SHARE`, `BAAS_BIN`, `JAR_NAME`; functions `resolve_version()`, `die()`, `usage()`. Tasks 5–8 extend this file.
  `scripts/tests/install-test.sh` provides `assert_eq`, `assert_contains`, `assert_fails`, `run_case`.

- [ ] **Step 1: Write the test harness and the first failing cases**

Create `scripts/tests/install-test.sh`:

```sh
#!/bin/sh
# POSIX test harness for scripts/install.sh. No framework: the repo has none, and one script
# does not justify adding one. Run from the repository root: sh scripts/tests/install-test.sh
set -u
INSTALLER="${INSTALLER:-scripts/install.sh}"
FAILURES=0
CASES=0

run_case() { CASES=$((CASES + 1)); printf '%s ... ' "$1"; }
pass()     { printf 'ok\n'; }
fail()     { FAILURES=$((FAILURES + 1)); printf 'FAIL\n  %s\n' "$1"; }

assert_contains() {
    case "$1" in
        *"$2"*) pass ;;
        *) fail "expected output to contain '$2', got: $1" ;;
    esac
}

assert_fails() {
    if [ "$1" -eq 0 ]; then fail "expected a non-zero exit, got 0"; else pass; fi
}

# --- version resolution -------------------------------------------------------

run_case "refuses the placeholder default"
out=$(sh "$INSTALLER" 2>&1); rc=$?
if [ "$rc" -eq 0 ]; then fail "expected refusal from an unreleased installer"
else assert_contains "$out" "--version"; fi

run_case "reports the baked default when --version has no value"
out=$(sh "$INSTALLER" --version 2>&1); rc=$?
assert_contains "$out" "0.0.0-semantically-released"

run_case "--version with no value exits non-zero"
sh "$INSTALLER" --version >/dev/null 2>&1; assert_fails $?

printf '\n%s case(s), %s failure(s)\n' "$CASES" "$FAILURES"
[ "$FAILURES" -eq 0 ]
```

- [ ] **Step 2: Run it and watch it fail**

Run: `sh scripts/tests/install-test.sh`
Expected: FAIL — `scripts/install.sh` does not exist, so every case errors.

- [ ] **Step 3: Write the installer skeleton**

Create `scripts/install.sh`:

```sh
#!/bin/sh
# baas installer. POSIX sh only — this runs on stock macOS as well as Linux.
#
# The version below is rewritten at release time by release.yml's prepareCmd, so the published
# installer names the release that published it and installs exactly that. The repository copy
# keeps the placeholder and refuses, mirroring pom.xml and RunCommand's refusal on an unreleased
# build: a checkout is the developer's special case, and it says so.
set -u

BAAS_VERSION_DEFAULT=0.0.0-semantically-released
PLACEHOLDER=0.0.0-semantically-released

BAAS_REPO="${BAAS_REPO:-wsztajerowski/benchmark-as-a-service}"
BAAS_SHARE="${BAAS_SHARE:-$HOME/.local/share/baas}"
BAAS_BIN="${BAAS_BIN:-$HOME/.local/bin}"
JAR_NAME=baas-cli.jar

die() { printf '%s\n' "$*" >&2; exit 1; }

usage() {
    cat <<USAGE
baas installer (installs $BAAS_VERSION_DEFAULT by default)

  install.sh                    install the default version
  install.sh --version <v>      install a specific version
  install.sh --update           install the newest release if newer than the installed one
  install.sh --uninstall        remove baas, leaving ~/.baas untouched

Environment: BAAS_VERSION, BAAS_REPO, BAAS_SHARE, BAAS_BIN, BAAS_JAVA
USAGE
}

MODE=install
REQUESTED_VERSION="${BAAS_VERSION:-}"

while [ $# -gt 0 ]; do
    case "$1" in
        --version)
            # The error doubles as the version report, so no separate print-version flag is needed.
            [ $# -ge 2 ] || die "--version requires a version, e.g. --version 1.2.0
(this installer installs $BAAS_VERSION_DEFAULT by default)"
            REQUESTED_VERSION="$2"; shift 2 ;;
        --update)    MODE=update; shift ;;
        --uninstall) MODE=uninstall; shift ;;
        -h|--help)   usage; exit 0 ;;
        *)           die "Unknown option: $1. Try --help." ;;
    esac
done

resolve_version() {
    if [ -n "$REQUESTED_VERSION" ]; then
        printf '%s' "$REQUESTED_VERSION"
        return 0
    fi
    [ "$BAAS_VERSION_DEFAULT" != "$PLACEHOLDER" ] || die \
"This installer is an unreleased copy from the repository, so it names no release to install.
Pass --version <v>, or fetch the published installer:
  curl -fsSL https://github.com/$BAAS_REPO/releases/latest/download/install.sh | sh"
    printf '%s' "$BAAS_VERSION_DEFAULT"
}

VERSION=$(resolve_version) || exit 1
```

- [ ] **Step 4: Run the tests**

Run: `sh scripts/tests/install-test.sh`
Expected: PASS — 3 cases, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add scripts/install.sh scripts/tests/install-test.sh
git commit -m "feat(install): installer skeleton with baked version resolution"
```

---

## Task 5: Fetch, verify and install atomically

**Files:**
- Modify: `scripts/install.sh`
- Modify: `scripts/tests/install-test.sh`

**Interfaces:**
- Consumes: Task 4's `resolve_version`, `die`, `BAAS_REPO`, `BAAS_SHARE`, `BAAS_BIN`, `JAR_NAME`.
- Produces: `asset_url()`, `fetch()`, `sha256_of()`, `verify()`, `install_jar()`, `write_shim()`.
  Task 6 calls `install_jar` and `write_shim`; Task 7 re-executes the installer wholesale.

- [ ] **Step 1: Add a fixture server to the harness, then failing cases**

Append to `scripts/tests/install-test.sh`, above the summary lines:

```sh
# --- fixture release ----------------------------------------------------------
# A local file:// "release" is enough to exercise fetch + verify + install without a network or a
# published release. BAAS_REPO is a variable precisely so a fork — or a test — can retarget it.

FIXTURE=$(mktemp -d)
trap 'rm -rf "$FIXTURE"' EXIT
mkdir -p "$FIXTURE/releases/download/v9.9.9-test"
printf 'not-really-a-jar' > "$FIXTURE/releases/download/v9.9.9-test/baas-cli.jar"
if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$FIXTURE/releases/download/v9.9.9-test/baas-cli.jar" | cut -d' ' -f1 \
        > "$FIXTURE/releases/download/v9.9.9-test/baas-cli.jar.sha256"
else
    shasum -a 256 "$FIXTURE/releases/download/v9.9.9-test/baas-cli.jar" | cut -d' ' -f1 \
        > "$FIXTURE/releases/download/v9.9.9-test/baas-cli.jar.sha256"
fi

SANDBOX=$(mktemp -d)
export BAAS_SHARE="$SANDBOX/share/baas" BAAS_BIN="$SANDBOX/bin"
export BAAS_BASE_URL="file://$FIXTURE"

run_case "installs a verified artifact"
out=$(sh "$INSTALLER" --version 9.9.9-test 2>&1); rc=$?
if [ "$rc" -ne 0 ]; then fail "install failed: $out"
elif [ ! -f "$BAAS_SHARE/baas-cli.jar" ]; then fail "jar not installed"
elif [ ! -x "$BAAS_BIN/baas" ]; then fail "shim not executable"
else pass; fi

run_case "rejects a corrupted artifact and writes nothing"
rm -rf "$SANDBOX"; mkdir -p "$SANDBOX"
printf 'deadbeef' > "$FIXTURE/releases/download/v9.9.9-test/baas-cli.jar.sha256"
sh "$INSTALLER" --version 9.9.9-test >/dev/null 2>&1; rc=$?
if [ "$rc" -eq 0 ]; then fail "expected checksum failure"
elif [ -f "$BAAS_SHARE/baas-cli.jar" ]; then fail "jar written despite mismatch"
else pass; fi
```

- [ ] **Step 2: Run and watch it fail**

Run: `sh scripts/tests/install-test.sh`
Expected: FAIL — the installer resolves a version and then does nothing.

- [ ] **Step 3: Add fetch and verification**

Append to `scripts/install.sh`:

```sh
BAAS_BASE_URL="${BAAS_BASE_URL:-https://github.com/$BAAS_REPO}"

asset_url() { printf '%s/releases/download/v%s/%s' "$BAAS_BASE_URL" "$1" "$2"; }

fetch() {
    # -L because release assets redirect to a CDN host.
    curl -fsSL "$1" -o "$2" || die "Could not fetch $1
Nothing was installed."
}

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | cut -d' ' -f1
    else
        # A missing tool is a failure, not a skip — the same rule the runner JAR lives under.
        die "No SHA-256 utility found (looked for sha256sum and shasum). Nothing was installed."
    fi
}

verify() {
    actual=$(sha256_of "$1")
    expected=$(tr -d ' \t\n\r' < "$2")
    [ "$actual" = "$expected" ] || die "Checksum mismatch for $(basename "$1").
  expected: $expected
  actual:   $actual
Nothing was installed."
}
```

- [ ] **Step 4: Add the atomic install and the shim**

```sh
install_jar() {
    version="$1"
    mkdir -p "$BAAS_SHARE" "$BAAS_BIN"
    # The staging directory sits INSIDE the destination directory on purpose: mv is only atomic
    # within one filesystem, and a rename is what lets a `baas run` already in flight keep its own
    # inode. Writing in place would corrupt a JVM that is currently babysitting a paid EC2 instance.
    stage="$BAAS_SHARE/.stage.$$"
    mkdir -p "$stage" || die "Cannot write to $BAAS_SHARE"
    # shellcheck disable=SC2064
    trap "rm -rf '$stage'" EXIT INT TERM

    fetch "$(asset_url "$version" "$JAR_NAME")" "$stage/$JAR_NAME"
    fetch "$(asset_url "$version" "$JAR_NAME.sha256")" "$stage/$JAR_NAME.sha256"
    verify "$stage/$JAR_NAME" "$stage/$JAR_NAME.sha256"

    mv -f "$stage/$JAR_NAME" "$BAAS_SHARE/$JAR_NAME"
    rm -rf "$stage"
    trap - EXIT INT TERM
}

write_shim() {
    stage="$BAAS_BIN/.baas.$$"
    cat > "$stage" <<SHIM
#!/bin/sh
# Generated by the baas installer. The Java runtime is resolved through PATH so that baas and the
# build that produced the benchmark JAR agree on a JVM; BAAS_JAVA overrides it. The guard is a
# shell builtin, so it forks nothing on any invocation.
command -v "\${BAAS_JAVA:-java}" >/dev/null 2>&1 || {
  echo "baas needs Java 25 on PATH, or BAAS_JAVA pointing at one." >&2; exit 1; }
exec "\${BAAS_JAVA:-java}" -jar "$BAAS_SHARE/$JAR_NAME" "\$@"
SHIM
    chmod +x "$stage"
    mv -f "$stage" "$BAAS_BIN/baas"
}
```

- [ ] **Step 5: Dispatch on the mode**

Append at the end of `scripts/install.sh`:

```sh
case "$MODE" in
    install)
        install_jar "$VERSION"
        write_shim
        printf 'Installed baas %s to %s\n' "$VERSION" "$BAAS_BIN/baas"
        ;;
esac
```

- [ ] **Step 6: Run the tests**

Run: `sh scripts/tests/install-test.sh`
Expected: PASS — 5 cases, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add scripts/install.sh scripts/tests/install-test.sh
git commit -m "feat(install): checksum-verified atomic install and launcher shim"
```

---

## Task 6: Prerequisites, PATH reporting and the stored installer

**Files:**
- Modify: `scripts/install.sh`
- Modify: `scripts/tests/install-test.sh`

**Interfaces:**
- Consumes: Task 5's `install_jar`, `write_shim`, `asset_url`, `fetch`, `die`.
- Produces: `check_prerequisites()`, `store_installer()`. Task 7's `--update` relies on
  `$BAAS_SHARE/install.sh` existing.

- [ ] **Step 1: Add failing cases**

```sh
run_case "stores an installer alongside the jar"
rm -rf "$SANDBOX"; mkdir -p "$SANDBOX"
cp "$INSTALLER" "$FIXTURE/releases/download/v9.9.9-test/install.sh"
# restore the good checksum clobbered by the previous case
sha256_fixture=$(command -v sha256sum >/dev/null 2>&1 \
    && sha256sum "$FIXTURE/releases/download/v9.9.9-test/baas-cli.jar" | cut -d' ' -f1 \
    || shasum -a 256 "$FIXTURE/releases/download/v9.9.9-test/baas-cli.jar" | cut -d' ' -f1)
printf '%s' "$sha256_fixture" > "$FIXTURE/releases/download/v9.9.9-test/baas-cli.jar.sha256"
sh "$INSTALLER" --version 9.9.9-test >/dev/null 2>&1
if [ -f "$BAAS_SHARE/install.sh" ]; then pass; else fail "no stored installer"; fi

run_case "never creates the baas configuration directory"
if [ -d "$SANDBOX/dot-baas" ]; then fail "installer touched configuration"; else pass; fi

run_case "reports PATH without editing shell configuration"
out=$(PATH=/usr/bin:/bin sh "$INSTALLER" --version 9.9.9-test 2>&1)
assert_contains "$out" "export PATH"
```

- [ ] **Step 2: Run and watch the first case fail**

Run: `sh scripts/tests/install-test.sh`
Expected: FAIL on "stores an installer alongside the jar".

- [ ] **Step 3: Store the installer and check prerequisites**

```sh
store_installer() {
    # NOT `cp "$0"`: in the dominant path the script arrives on stdin through `curl | sh`, where
    # $0 is `sh` and there is no file to copy. Downloading the pinned installer is correct in every
    # path and guarantees the stored copy is the released one for exactly this version.
    stage="$BAAS_SHARE/.install.$$"
    fetch "$(asset_url "$1" install.sh)" "$stage"
    chmod +x "$stage"
    mv -f "$stage" "$BAAS_SHARE/install.sh"
}

java_major() {
    # java -version writes to stderr, and reports "1.8.0_402" for 8 but "25.0.4" for modern
    # releases — both shapes have to parse.
    raw=$("${BAAS_JAVA:-java}" -version 2>&1 | head -1 | sed -n 's/.*version "\([0-9][0-9]*\)\.*.*/\1/p')
    [ -n "$raw" ] || return 1
    if [ "$raw" = "1" ]; then printf '8'; else printf '%s' "$raw"; fi
}

check_prerequisites() {
    command -v "${BAAS_JAVA:-java}" >/dev/null 2>&1 \
        || die "baas needs Java 25. No java found on PATH (set BAAS_JAVA to override).
Nothing was installed."
    major=$(java_major) || die "Could not determine the Java version. Nothing was installed."
    [ "$major" -ge 25 ] 2>/dev/null \
        || die "baas needs Java 25; found Java $major. Nothing was installed."

    command -v git >/dev/null 2>&1 \
        || printf 'warning: git not found. baas run derives project, commit and branch from git;\n         pass --project, --commit and --branch instead.\n' >&2

    case ":$PATH:" in
        *":$BAAS_BIN:"*) ;;
        *) printf '\n%s is not on your PATH. Add it:\n\n    export PATH="%s:$PATH"\n\n' \
               "$BAAS_BIN" "$BAAS_BIN" ;;
    esac
}
```

The installer never edits a shell configuration file: which file to edit varies by shell, doing it
twice is easy, and `--uninstall` could not cleanly reverse it.

- [ ] **Step 4: Wire them into the install mode**

```sh
    install)
        check_prerequisites
        install_jar "$VERSION"
        write_shim
        store_installer "$VERSION"
        printf 'Installed baas %s to %s\n' "$VERSION" "$BAAS_BIN/baas"
        printf '(this installer is pinned to %s — re-fetch it for a newer release)\n' "$VERSION"
        ;;
```

- [ ] **Step 5: Run the tests**

Run: `sh scripts/tests/install-test.sh`
Expected: PASS — 8 cases, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add scripts/install.sh scripts/tests/install-test.sh
git commit -m "feat(install): prerequisite checks, PATH reporting and a stored installer"
```

---

## Task 7: `--update` resolves the newest release and hands off

**Files:**
- Modify: `scripts/install.sh`
- Modify: `scripts/tests/install-test.sh`

**Interfaces:**
- Consumes: Task 6's stored installer at `$BAAS_SHARE/install.sh`; Task 5's `die`, `BAAS_BASE_URL`.
- Produces: `latest_tag()`, `installed_version()`, `version_newer()` (exit 0 when `$1` is strictly
  newer than `$2`), and the `update` mode.

- [ ] **Step 1: Add failing cases**

```sh
run_case "orders versions numerically, not lexically"
if sh scripts/tests/version-compare-probe.sh 3.10.0 3.9.0 \
   && ! sh scripts/tests/version-compare-probe.sh 3.9.0 3.10.0 \
   && ! sh scripts/tests/version-compare-probe.sh 2.1.0 2.1.0; then pass
else fail "3.10.0 should outrank 3.9.0, and equal versions are not newer"; fi

run_case "update reports current and downloads nothing"
out=$(BAAS_LATEST_TAG=9.9.9-test sh "$INSTALLER" --update 2>&1)
assert_contains "$out" "current"

run_case "update refuses to downgrade"
out=$(BAAS_LATEST_TAG=0.0.1 sh "$INSTALLER" --update 2>&1)
assert_contains "$out" "newer than"
```

Create `scripts/tests/version-compare-probe.sh`, which exists only so the comparison is testable
without running an install:

```sh
#!/bin/sh
# Probe for install.sh's version_newer(). Exits 0 when $1 is strictly newer than $2.
set -u
BAAS_PROBE=1 . ./scripts/install.sh
version_newer "$1" "$2"
```

For that `.` to work, sourcing must define the functions without executing anything. Guard both
executable regions of `install.sh` — the `while [ $# -gt 0 ]` argument loop written in Task 4 and the
`case "$MODE"` dispatch written in Task 5 — by wrapping each in:

```sh
if [ -z "${BAAS_PROBE:-}" ]; then
    # ... the region as already written ...
fi
```

Nothing inside either region changes; only the guard is added. `BAAS_PROBE` is set by the probe
script and by nothing else.

- [ ] **Step 2: Run and watch it fail**

Run: `sh scripts/tests/install-test.sh`
Expected: FAIL — `version_newer` is undefined and `--update` is unhandled.

- [ ] **Step 3: Implement version comparison**

```sh
# Field-wise and numeric. A string comparison ranks 3.9.0 above 3.10.0, which is the classic
# version-sort bug; sort -V would avoid it but is GNU-only in practice.
version_newer() {
    a="$1"; b="$2"
    [ "$a" != "$b" ] || return 1
    i=1
    while [ "$i" -le 3 ]; do
        fa=$(printf '%s' "$a" | cut -d. -f"$i")
        fb=$(printf '%s' "$b" | cut -d. -f"$i")
        fa=${fa:-0}; fb=${fb:-0}
        case "$fa$fb" in
            *[!0-9]*) die "Cannot compare versions $a and $b. Pass --version <v> explicitly." ;;
        esac
        [ "$fa" -eq "$fb" ] || { [ "$fa" -gt "$fb" ]; return $?; }
        i=$((i + 1))
    done
    return 1
}
```

- [ ] **Step 4: Resolve the newest tag and the installed version**

```sh
latest_tag() {
    # One request. BAAS_LATEST_TAG is a test seam; nothing else sets it.
    [ -z "${BAAS_LATEST_TAG:-}" ] || { printf '%s' "$BAAS_LATEST_TAG"; return 0; }
    url=$(curl -fsSLI -o /dev/null -w '%{url_effective}' \
        "$BAAS_BASE_URL/releases/latest") || die "Could not determine the newest release."
    tag=${url##*/tag/}
    [ "$tag" != "$url" ] || die "Could not determine the newest release from: $url"
    printf '%s' "${tag#v}"
}

installed_version() {
    # baas --version reads Implementation-Version out of the jar manifest — the same value that
    # pins the runner JAR, so it cannot drift from what is actually installed. A VERSION marker
    # file would avoid this parsing but could disagree with the jar beside it.
    [ -x "$BAAS_BIN/baas" ] || return 1
    "$BAAS_BIN/baas" --version 2>/dev/null | awk 'NR==1 {print $2}'
}
```

- [ ] **Step 5: Implement the update mode**

```sh
    update)
        current=$(installed_version) || die \
"No installed baas found at $BAAS_BIN/baas, so there is nothing to update.
Install it first:
  curl -fsSL https://github.com/$BAAS_REPO/releases/latest/download/install.sh | sh"
        [ -n "$current" ] || die "Could not read the installed version. Nothing was changed."
        newest=$(latest_tag) || exit 1

        if [ "$current" = "$newest" ]; then
            printf 'baas %s is current. Nothing to do.\n' "$current"
            exit 0
        fi
        if version_newer "$current" "$newest"; then
            printf 'Installed baas %s is newer than the newest release (%s). Nothing changed.\n' \
                "$current" "$newest"
            printf 'Use --version %s to install it deliberately.\n' "$newest"
            exit 0
        fi

        # Hand off rather than install. The script that installs version X must always be version
        # X's own script: if a later release moves the layout, an older installer would place the
        # newer jar wrongly, which is exactly what baking the version was meant to prevent.
        printf 'Updating baas %s -> %s\n' "$current" "$newest"
        exec sh -c "curl -fsSL '$BAAS_BASE_URL/releases/download/v$newest/install.sh' | sh"
        ;;
```

- [ ] **Step 6: Run the tests**

Run: `sh scripts/tests/install-test.sh`
Expected: PASS — 11 cases, 0 failures.

- [ ] **Step 7: Commit**

```bash
git add scripts/install.sh scripts/tests/install-test.sh scripts/tests/version-compare-probe.sh
git commit -m "feat(install): --update resolves the newest release and hands off to its installer"
```

---

## Task 8: `--uninstall` removes exactly what was installed

**Files:**
- Modify: `scripts/install.sh`
- Modify: `scripts/tests/install-test.sh`

**Interfaces:**
- Consumes: `BAAS_SHARE`, `BAAS_BIN`, `JAR_NAME`.
- Produces: the `uninstall` mode. Task 10's CI job asserts its behaviour.

- [ ] **Step 1: Add failing cases**

```sh
run_case "uninstall removes the command"
rm -rf "$SANDBOX"; mkdir -p "$SANDBOX"
sh "$INSTALLER" --version 9.9.9-test >/dev/null 2>&1
CONFIG="$SANDBOX/dot-baas/config.yaml"
mkdir -p "$(dirname "$CONFIG")"; printf 'prefix: sentinel\n' > "$CONFIG"
sh "$INSTALLER" --uninstall >/dev/null 2>&1
if [ -e "$BAAS_BIN/baas" ]; then fail "shim survived"
elif [ -e "$BAAS_SHARE/$JAR_NAME" ]; then fail "jar survived"
else pass; fi

run_case "uninstall leaves the configuration byte-for-byte"
if [ "$(cat "$CONFIG")" = "prefix: sentinel" ]; then pass
else fail "configuration was modified or removed"; fi
```

- [ ] **Step 2: Run and watch it fail**

Run: `sh scripts/tests/install-test.sh`
Expected: FAIL — `--uninstall` is unhandled.

- [ ] **Step 3: Implement uninstall**

```sh
    uninstall)
        removed=0
        for target in "$BAAS_BIN/baas" "$BAAS_SHARE/$JAR_NAME" "$BAAS_SHARE/install.sh"; do
            if [ -e "$target" ]; then rm -f "$target"; removed=$((removed + 1)); fi
        done
        # rmdir, never rm -rf: it removes the directory only when this installer's files were the
        # last things in it, so anything a user put there survives.
        [ ! -d "$BAAS_SHARE" ] || rmdir "$BAAS_SHARE" 2>/dev/null || true
        if [ "$removed" -eq 0 ]; then
            printf 'No baas installation found under %s.\n' "$BAAS_SHARE"
        else
            printf 'Removed baas. Your configuration in ~/.baas was not touched.\n'
        fi
        ;;
```

- [ ] **Step 4: Run the tests**

Run: `sh scripts/tests/install-test.sh`
Expected: PASS — 13 cases, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add scripts/install.sh scripts/tests/install-test.sh
git commit -m "feat(install): --uninstall, leaving ~/.baas untouched"
```

---

## Task 9: The release publishes the checksum, the installer and its baked version

**Files:**
- Modify: `.github/workflows/release.yml:44-66`

**Interfaces:**
- Consumes: `scripts/install.sh` with a `BAAS_VERSION_DEFAULT=` line at column 0.
- Produces: release assets `baas-cli.jar.sha256` and `install.sh`, the latter version-baked.

- [ ] **Step 1: Add the CLI checksum and the version bake to `prepareCmd`**

In the generated `release.config.js`, `prepareCmd` currently reads:

```
mvn --batch-mode versions:set -DnewVersion=${nextRelease.version} && mvn --batch-mode -DskipTests package && sha256sum benchmark-runner/target/benchmark-runner.jar | cut -d" " -f1 > benchmark-runner/target/benchmark-runner.jar.sha256
```

Append two commands. Order matters: the checksum must be computed **after** `versions:set` and the
repackage, or it covers a placeholder-versioned JAR.

```
 && sha256sum baas-cli/target/baas-cli.jar | cut -d" " -f1 > baas-cli/target/baas-cli.jar.sha256 && sed -i "s/^BAAS_VERSION_DEFAULT=.*/BAAS_VERSION_DEFAULT=${nextRelease.version}/" scripts/install.sh
```

The job runs on `ubuntu-latest`, so GNU `sed -i` and `sha256sum` are safe here. Portability is a
constraint on the installer, which runs on the user's machine — not on this workflow.

- [ ] **Step 2: Add the two assets**

```js
                  'assets': [
                    {'path': 'benchmark-runner/target/benchmark-runner.jar', 'label': 'benchmark-runner.jar'},
                    {'path': 'benchmark-runner/target/benchmark-runner.jar.sha256', 'label': 'benchmark-runner.jar.sha256'},
                    {'path': 'baas-cli/target/baas-cli.jar', 'label': 'baas-cli.jar'},
                    {'path': 'baas-cli/target/baas-cli.jar.sha256', 'label': 'baas-cli.jar.sha256'},
                    {'path': 'scripts/install.sh', 'label': 'install.sh'}
                  ]
```

- [ ] **Step 3: Confirm nothing is committed back**

Read the plugin list and confirm `@semantic-release/git` is absent. It must stay absent: the
repository keeps `BAAS_VERSION_DEFAULT=0.0.0-semantically-released` exactly as `pom.xml` keeps its
placeholder, and the mutation exists only in the build workspace.

- [ ] **Step 4: Dry-run the bake locally**

```bash
cp scripts/install.sh /tmp/install.sh.bak
sed -i.bak "s/^BAAS_VERSION_DEFAULT=.*/BAAS_VERSION_DEFAULT=9.9.9/" scripts/install.sh
grep '^BAAS_VERSION_DEFAULT=' scripts/install.sh   # expect: BAAS_VERSION_DEFAULT=9.9.9
cp /tmp/install.sh.bak scripts/install.sh
git diff --exit-code scripts/install.sh            # expect: no diff
```

Note the `sed -i.bak` form: this step runs on the developer's machine, which may be macOS.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/release.yml
git commit -m "ci(release): publish baas-cli.jar.sha256 and a version-baked install.sh"
```

---

## Task 10: CI installs from a fixture release on macOS and Linux

**Files:**
- Create: `.github/workflows/install-test.yml`

**Interfaces:**
- Consumes: `scripts/install.sh`, `scripts/tests/install-test.sh`, and `BAAS_BASE_URL` as the
  retarget seam.
- Produces: PR-time coverage of the installer on both platforms.

- [ ] **Step 1: Create the workflow**

```yaml
name: Installer

on:
  pull_request:
  push:
    branches: [main]

jobs:
  install:
    name: install.sh on ${{ matrix.os }}
    runs-on: ${{ matrix.os }}
    strategy:
      fail-fast: false
      matrix:
        os: [ubuntu-latest, macos-latest]
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v5
        with:
          java-version: 25
          distribution: temurin
          cache: maven

      # A fixture release, not a published one. No published release carries baas-cli.jar.sha256 —
      # this change is what starts publishing it — so pinning to one would fail on every run until
      # after the first release. Building the artifact here also proves the chain the whole change
      # rests on: versions:set reaching the shaded manifest, and a checksum computed after the
      # repackage matching it.
      - name: Build a fixture release
        run: |
          mvn --batch-mode versions:set -DnewVersion=9.9.9-ci
          mvn --batch-mode -DskipTests package
          mkdir -p fixture/releases/download/v9.9.9-ci
          cp baas-cli/target/baas-cli.jar fixture/releases/download/v9.9.9-ci/
          cp scripts/install.sh           fixture/releases/download/v9.9.9-ci/
          if command -v sha256sum >/dev/null 2>&1; then
            sha256sum baas-cli/target/baas-cli.jar | cut -d' ' -f1 \
              > fixture/releases/download/v9.9.9-ci/baas-cli.jar.sha256
          else
            shasum -a 256 baas-cli/target/baas-cli.jar | cut -d' ' -f1 \
              > fixture/releases/download/v9.9.9-ci/baas-cli.jar.sha256
          fi

      - name: Run the installer test suite
        run: sh scripts/tests/install-test.sh

      - name: Install from the fixture
        env:
          BAAS_BASE_URL: file://${{ github.workspace }}/fixture
        run: sh scripts/install.sh --version 9.9.9-ci

      # The single assertion covering the failure class this change exists to prevent, and the one
      # that pins the `baas <version>` output format --update parses.
      - name: The installed CLI reports the fixture version
        run: |
          export PATH="$HOME/.local/bin:$PATH"
          actual=$(baas --version)
          echo "baas --version -> $actual"
          [ "$actual" = "baas 9.9.9-ci" ] || { echo "expected 'baas 9.9.9-ci'"; exit 1; }

      - name: A corrupted checksum fails the install
        env:
          BAAS_BASE_URL: file://${{ github.workspace }}/fixture
        run: |
          echo deadbeef > fixture/releases/download/v9.9.9-ci/baas-cli.jar.sha256
          rm -rf "$HOME/.local/share/baas"
          if sh scripts/install.sh --version 9.9.9-ci; then
            echo "expected a checksum failure"; exit 1
          fi
          [ ! -f "$HOME/.local/share/baas/baas-cli.jar" ] || { echo "jar written"; exit 1; }

      - name: Uninstall leaves the configuration intact
        run: |
          mkdir -p "$HOME/.baas" && echo 'prefix: sentinel' > "$HOME/.baas/config.yaml"
          sh scripts/install.sh --uninstall
          [ ! -e "$HOME/.local/bin/baas" ] || { echo "shim survived"; exit 1; }
          [ "$(cat "$HOME/.baas/config.yaml")" = "prefix: sentinel" ] \
            || { echo "configuration touched"; exit 1; }
```

- [ ] **Step 2: Verify the macOS leg exercises `shasum`**

Add a step before the fixture build that fails loudly if the assumption breaks:

```yaml
      - name: Record which checksum utility this runner has
        run: |
          command -v sha256sum || echo "no sha256sum (expected on macOS)"
          command -v shasum    || echo "no shasum"
```

Expected on `macos-latest`: no `sha256sum`, `shasum` present. If `sha256sum` ever appears there, the
macOS leg has stopped covering the split and the detection logic needs a dedicated test.

- [ ] **Step 3: Push and confirm both legs are green**

```bash
git add .github/workflows/install-test.yml
git commit -m "ci: exercise install.sh against a fixture release on macOS and Linux"
git push
```

Expected: both matrix legs pass. If the macOS leg fails on checksum comparison, the cause is almost
certainly `shasum` output formatting — it emits `<hash>  <file>`, and `cut -d' ' -f1` handles the
double space only because `cut` treats each space as its own delimiter and the first field is the
hash.

---

## Task 11: Invariants, review findings and the ADR

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/review/baas-cli-findings.md`
- Modify: `docs/adr/0001-self-contained-baas-cli.md`

**Interfaces:**
- Consumes: the behaviour built in Tasks 1–10.
- Produces: documentation matching the code, so the next reader is not misled.

- [ ] **Step 1: Correct CLAUDE.md's stale claims**

Four edits:

1. In *What isn't there*: `"Nothing in CI invokes scripts/. release.yml builds its semantic-release
   config inline and shells out only to mvn, so CI does not protect those three utilities."` — now
   false in both halves. The release workflow `sed`s `scripts/install.sh`, and `install-test.yml`
   executes it.
2. In *Gotchas*: delete `"baas run builds in the current working directory — the user's benchmark
   project, not this repo."`
3. In *Accepted risks*, the `baas run` project layout row: `"Assumes a Maven project producing one
   JAR; --benchmark-jar + --skip-build covers the rest."` — rewrite; there is no build and
   `--benchmark-jar` is required.
4. In *Accepted risks*, the Distribution row: narrow it. The shaded JAR is now installable via
   `scripts/install.sh`; Homebrew, jpackage, native image and Docker remain unbuilt backlog.

- [ ] **Step 2: Add the two new invariants**

Under *Invariants*, add:

```markdown
- **The installer installs released artifacts only, and the repository copy refuses.**
  `scripts/install.sh` carries `BAAS_VERSION_DEFAULT`, rewritten at release time by `release.yml`'s
  `prepareCmd` and never committed back. A checkout copy holds the placeholder and exits naming
  `--version`, the same no-fallback stance `RunCommand` takes on an unreleased build. `--update`
  never installs anything itself: it resolves the newest release and re-executes *that release's*
  installer, so the script installing version X is always version X's own.
- **`commit` and `branch` are absent when unresolved, never `"unknown"`.** A placeholder value is
  indistinguishable from a real one at query time, which is how `RESULT#unknown` accumulated. Tags
  are the entire query surface, so a fake value there is worse than a missing one.
```

- [ ] **Step 3: File A10**

In `docs/review/baas-cli-findings.md`, add to the status table:

```markdown
| 15 | A10 | Caller-ARN prefix is unnormalised, so an SSO identity moves it every session | Med | Open |
```

and the entry body:

```markdown
## 15. A10 — the caller-ARN prefix is unnormalised · Med · Open

`SetupCommand.computePrefix` hashes the raw ARN from `sts:GetCallerIdentity`. For an IAM user
(`arn:aws:iam::123:user/alice`) that is stable. For an SSO or assumed-role identity the ARN is
`arn:aws:sts::123:assumed-role/Role/<session-name>`, and the session name changes per login.

Only `SetupCommand:76` and `DeployerPolicyCommand:54` derive the prefix; every other command reads
`config.getPrefix()`. So day-to-day use is unaffected, and the damage is concentrated in a second
`baas admin setup`: it creates a duplicate stack, bucket and results table — both retained by
`DeletionPolicy` — and rewrites `~/.baas/config.yaml` to point at the empty one. It presents as
"all my benchmark history is gone". The history is intact in the first table; nothing says so.
`baas admin deployer-policy` compounds it by rendering a policy for a prefix that does not match
the user's existing stack.

**Proposed fix:** normalise `assumed-role` ARNs to their role ARN
(`arn:aws:sts::123:assumed-role/Role/session` → `arn:aws:iam::123:role/Role`) before hashing. This
does not migrate anything: IAM-user ARNs hash exactly as before, so no existing prefix moves.
Tracked as its own OpenSpec change, deferred.
```

- [ ] **Step 4: Narrow A6 and correct the exclusion list**

A6 is *"Config: silent unknown keys, no schema version, stale default"*. Its stale-default component
is closed — `benchmark.jarPath` no longer exists. Leave the status **Open** and note in the entry
which component closed and when; the silent-unknown-keys and schema-version components remain.

In *Deliberately excluded*, the list names `"distribution mechanism"` among accepted risks not to be
re-raised. Narrow it the same way CLAUDE.md's Distribution row is narrowed.

- [ ] **Step 5: Amend the ADR**

`docs/adr/0001-self-contained-baas-cli.md` states distribution *"remains backlog, not a decision this
ADR records as made."* Add a bullet to *Amendments since acceptance* rather than editing the original
text — the file's own convention is that amendments accrete and the code wins.

- [ ] **Step 6: Commit**

```bash
git add CLAUDE.md docs/review/baas-cli-findings.md docs/adr/0001-self-contained-baas-cli.md
git commit -m "docs: record the installer invariants, file A10, narrow A6 and Distribution"
```

---

## Task 12: Documentation (scope B), written last

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: everything built in Tasks 1–11. Written last so it describes what exists.

- [ ] **Step 1: Replace the build-and-alias section**

`README.md:24-33` currently instructs `mvn clean package -DskipTests` and an alias, stating *"There
is no packaged binary yet."* Replace with the install one-liner:

```bash
curl -fsSL https://github.com/wsztajerowski/benchmark-as-a-service/releases/latest/download/install.sh | sh
```

Keep the alias, demoted to a *Developing BaaS itself* subsection: a reactor build carries the
placeholder version and `baas run` refuses it without `--runner-jar`, which is the point.

- [ ] **Step 2: Correct Requirements**

`README.md:16-22` requires *"Java 25 and Maven"*. Maven is no longer a prerequisite of `baas` — you
need it (or Gradle, or anything) to build your own benchmark JAR, which is a different statement.
Add that the AWS CLI is needed **only** if you authenticate through SSO, because `aws sso login` is
what writes the token cache the Java SDK reads.

- [ ] **Step 3: Document the installer's own options**

`--version <v>`, `--update`, `--uninstall`, and the `BAAS_JAVA` override. State plainly that a
*saved* `install.sh` reinstalls its own version rather than upgrading, and that upgrading means
re-fetching the one-liner or running `~/.local/share/baas/install.sh --update`.

- [ ] **Step 4: Document the new `baas run` shape**

```bash
mvn package
baas run --benchmark-jar target/benchmarks.jar jmh -- MyBenchmark -f 1 -wi 1 -i 3
```

Note that `--` is still required before benchmark parameters, and that `commit` and `branch` are
derived from the working directory's git repository — so a JAR built elsewhere may be tagged with a
commit it did not come from. `--commit` and `--branch` are the override.

- [ ] **Step 5: Add the SSO warning from A10**

One sentence where `baas admin setup` is introduced: if you reach AWS through SSO, your caller ARN
carries a per-session name, so a second `baas admin setup` creates separate infrastructure and
repoints your configuration at it. Until A10 is fixed, run `setup` from a stable identity.

- [ ] **Step 6: Walk the path on a clean machine**

Follow the README end to end as a stranger would: install, credentials, `baas admin deployer-policy`,
`baas admin setup`, `baas admin build-image`, first run. Fix what is stale. This is the step that
makes scope B worth doing — everything above is editing, this is verification.

- [ ] **Step 7: Commit**

```bash
git add README.md
git commit -m "docs: install instructions, the new baas run shape and the SSO caveat"
```

---

## Manual verification

No automated test covers `baas run`: `RunCommand.call()` is executed by no test, and
`e2e-cloud-test.yml` drives the GitHub Actions path instead. Everything below is by hand, after the
first release that publishes the installer.

- [ ] **M1.** Install on macOS and on Linux from the documented one-liner; `baas --version` reports
  that release, not the placeholder.
- [ ] **M2.** `baas run --benchmark-jar <jar> jmh -- <benchmark>` completes against real AWS, pinning
  `releases/<version>/benchmark-runner.jar`. **This path has never executed.**
- [ ] **M3.** The stored measurement carries `commit` and `branch` when run from a checkout; a run
  from outside a repository stores neither and still succeeds.
- [ ] **M4.** `--update` on a current installation reports current and downloads nothing; on an older
  one it installs the newer release.
- [ ] **M5.** Start a `baas run`, and while it polls, run the installer again. The in-flight run
  completes normally — this is what the atomic rename in Task 5 exists for.
- [ ] **M6.** `--uninstall`, confirm `~/.baas/config.yaml` is untouched, reinstall, and confirm `baas`
  works without reconfiguration.
