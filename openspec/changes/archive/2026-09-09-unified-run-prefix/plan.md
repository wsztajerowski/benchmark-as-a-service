# Unified Run Prefix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every benchmark run one S3 prefix, one identifier and one instant, and make the runner JAR a run executes determined by the CLI that launched it rather than by the calendar.

**Architecture:** A new `RunId`/`RunLayout` pair in `baas-model` becomes the single place a run identifier and a run prefix are constructed, mirroring what `ResultKeys` already does for DynamoDB keys. `baas run` mints the id from one `Instant.now()` and passes that same instant to the runner as `--created-at`, so the prefix name and the stored `createdAt` cannot disagree. User-data stops calling GitHub and always copies its runner JAR from `releases/<version>/` in the working bucket, which the CLI seeds — checksum-verified — from the release matching its own version.

**Tech Stack:** Java 25, Maven multi-module (`baas-model`, `baas-cli`, `benchmark-runner`), picocli, AWS SDK v2 (S3, DynamoDB, EC2, SSM), JUnit 6.0.2, AssertJ, Testcontainers 2.x + LocalStack, CloudFormation, GitHub Actions + semantic-release.

**Spec:** `openspec/changes/unified-run-prefix/` — `proposal.md` (why), `design.md` (how and why-not), `specs/*/spec.md` (the requirements each task satisfies), `tasks.md` (the checklist this plan decomposes).

## Global Constraints

Copied from `CLAUDE.md`; every task's requirements implicitly include these.

- `pom.xml` version stays `0.0.0-semantically-released`. Never bump by hand.
- Shaded artifacts are `target/baas-cli.jar` and `target/benchmark-runner.jar` — stable paths, no version suffix.
- **No `set -e` in user-data.** Errors are handled by exit code and the `run-status` sentinel; `set -e` before the watchdog starts orphans a paid instance.
- The watchdog starts immediately after `INSTANCE_ID` resolves. Every later failure must be covered by it.
- User-data installs nothing — no `yum`, no downloads of toolchain.
- **Every manifest value is captured into a shell variable first.** The heredoc body contains only `${VAR}` references; values that can contain `"` or `\` go through `json_escape`.
- The benchmark runs from `/app`, never `/`.
- DynamoDB keys are constructed only in `ResultKeys`; items only in `MeasurementItemMapper`. Both in `baas-model`.
- Command payloads go to `System.out`; diagnostics go to the logger (stderr).
- `printJson`/`printCsv` format with `Locale.ROOT`.
- `mvn -pl benchmark-runner verify` alone fails — it needs the fake-benchmark shaded JARs. Run the full reactor first: `mvn -B -DskipTests install`.
- `ASYNC_PATH` must be exported or `JmhWithAsyncProfilerSubcommandServiceIT` silently skips.
- The rendered deployer policy must stay under 4608 non-whitespace characters; the rendered image component under 4096 bytes.
- JUnit **6**, not 5. Testcontainers **2.x**.

## File Structure

**Created**

- `baas-model/src/main/java/pl/wsztajerowski/baas/model/RunId.java` — mints and validates the identifier. No parsing beyond the instant.
- `baas-model/src/main/java/pl/wsztajerowski/baas/model/RunLayout.java` — the only constructor of `runs/<project>/<runId>` and its `input/` sub-prefix, plus `releases/<version>/benchmark-runner.jar`.
- `baas-model/src/test/java/pl/wsztajerowski/baas/model/RunIdTest.java`
- `baas-model/src/test/java/pl/wsztajerowski/baas/model/RunLayoutTest.java`
- `baas-cli/src/main/java/pl/wsztajerowski/baas/BaasVersion.java` — reads the CLI's own version from the manifest.
- `baas-cli/src/main/java/pl/wsztajerowski/baas/infra/RunnerJarResolver.java` — seeds and resolves `releases/<version>/benchmark-runner.jar`.
- `baas-cli/src/test/java/pl/wsztajerowski/baas/BaasVersionTest.java`
- `baas-cli/src/test/java/pl/wsztajerowski/baas/infra/RunnerJarResolverTest.java`
- `scripts/migrate-run-layout.sh` — throwaway, deleted in Task 10's final commit.

**Modified**

- `baas-model/src/main/java/pl/wsztajerowski/baas/model/TagKeys.java:16` — add `BRANCH`.
- `benchmark-runner/src/main/java/pl/wsztajerowski/commands/ApiCommonSharedOptions.java:20-83` — `--created-at`, shared defaults, hard-fail project.
- `baas-cli/src/main/java/pl/wsztajerowski/baas/commands/RunCommand.java:210-230,423` — one id, `input/` uploads, `branch` tag, `--git-common-dir`.
- `baas-cli/src/main/java/pl/wsztajerowski/baas/commands/GitProject.java:28` — resolve the main repository.
- `baas-cli/src/main/java/pl/wsztajerowski/baas/infra/UserDataScriptBuilder.java:117-127,192-196` — drop the GitHub branch, add `--created-at`, manifest identity fields.
- `baas-cli/src/main/java/pl/wsztajerowski/baas/commands/DownloadCommand.java:36-58` — accept a run id.
- `baas-cli/src/main/java/pl/wsztajerowski/baas/results/ResultsQueryService.java:100-109` — widen `REQUEST_ID`, drop truncation.
- `baas-cli/pom.xml:146-148` — `Implementation-Version` in the shade manifest.
- `infra/cf-template-core.yaml:183-205` — suspended versioning, one lifecycle rule deleted.
- `infra/cf-template-ci.yaml:116` — dead `ci/*` grant removed.
- `.github/workflows/release.yml:56,64` — asset list and `prepareCmd`.
- `.github/workflows/e2e-cloud-test.yml:26-28,96,119` — per-job run ids.
- `CLAUDE.md`, `docs/review/baas-cli-findings.md`, `docs/review/benchmark-runner-findings.md`.

---

## Task 1: CLI version readability and the release pipeline

Nothing downstream works until a published release carries its own version. This task is first for that reason alone.

**Files:**
- Modify: `baas-cli/pom.xml:138-153`
- Create: `baas-cli/src/main/java/pl/wsztajerowski/baas/BaasVersion.java`
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/BaasVersionTest.java`
- Modify: `.github/workflows/release.yml:43-67`

**Interfaces:**
- Consumes: nothing.
- Produces: `BaasVersion.current()` → `String` (the raw version, possibly the placeholder); `BaasVersion.isReleased()` → `boolean`; `BaasVersion.PLACEHOLDER` → `String` constant `"0.0.0-semantically-released"`.

- [ ] **Step 1: Write the failing test**

```java
package pl.wsztajerowski.baas;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BaasVersionTest {

    @Test
    void placeholderIsNotAReleasedVersion() {
        assertThat(BaasVersion.isReleased(BaasVersion.PLACEHOLDER)).isFalse();
    }

    @Test
    void absentManifestEntryReadsAsThePlaceholder() {
        assertThat(BaasVersion.isReleased(null)).isFalse();
        assertThat(BaasVersion.isReleased("  ")).isFalse();
    }

    @Test
    void aRealVersionIsReleased() {
        assertThat(BaasVersion.isReleased("1.4.2")).isTrue();
    }

    @Test
    void currentNeverReturnsNull() {
        assertThat(BaasVersion.current()).isNotNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -B -pl baas-model,baas-cli -am test -Dtest=BaasVersionTest`
Expected: FAIL — compilation error, `BaasVersion` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package pl.wsztajerowski.baas;

/**
 * The CLI's own released version, which is what pins the runner JAR a run executes.
 *
 * <p>A reactor build has no released version, and there is deliberately no fallback: two
 * provisioning paths would produce silently incomparable results, the same reason {@code baas run}
 * refuses to launch without a built AMI.
 */
public final class BaasVersion {

    public static final String PLACEHOLDER = "0.0.0-semantically-released";

    private BaasVersion() {}

    public static String current() {
        String v = BaasVersion.class.getPackage().getImplementationVersion();
        return v == null || v.isBlank() ? PLACEHOLDER : v;
    }

    public static boolean isReleased() {
        return isReleased(current());
    }

    static boolean isReleased(String version) {
        return version != null && !version.isBlank() && !PLACEHOLDER.equals(version);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -B -pl baas-model,baas-cli -am test -Dtest=BaasVersionTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Stamp the version into the shaded manifest**

In `baas-cli/pom.xml`, extend the existing `ManifestResourceTransformer` (which today declares only `mainClass`):

```xml
<transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
    <mainClass>pl.wsztajerowski.baas.BaasApp</mainClass>
    <manifestEntries>
        <Implementation-Version>${project.version}</Implementation-Version>
    </manifestEntries>
</transformer>
```

- [ ] **Step 6: Verify the stamp lands in the JAR**

Run: `mvn -B -pl baas-model,baas-cli -am -DskipTests package && unzip -p baas-cli/target/baas-cli.jar META-INF/MANIFEST.MF | grep Implementation-Version`
Expected: `Implementation-Version: 0.0.0-semantically-released` — the placeholder, because this is a reactor build. That is the correct output; it proves the mechanism without a release.

- [ ] **Step 7: Rebuild after `versions:set` in `release.yml`**

In the `Generate release configuration` step, replace the `@semantic-release/exec` `prepareCmd`. The current one only sets the version, so the assets uploaded in the `publish` phase are still the ones built by the workflow's initial `mvn -B verify` — carrying the placeholder.

```
'prepareCmd': 'mvn --batch-mode versions:set -DnewVersion=\${nextRelease.version} && mvn --batch-mode -DskipTests package && sha256sum benchmark-runner/target/benchmark-runner.jar | cut -d\" \" -f1 > benchmark-runner/target/benchmark-runner.jar.sha256',
```

- [ ] **Step 8: Publish the CLI JAR and the checksum as assets**

In the same step, replace the `@semantic-release/github` `assets` array:

```
'assets': [
  {'path': 'benchmark-runner/target/benchmark-runner.jar', 'label': 'benchmark-runner.jar'},
  {'path': 'benchmark-runner/target/benchmark-runner.jar.sha256', 'label': 'benchmark-runner.jar.sha256'},
  {'path': 'baas-cli/target/baas-cli.jar', 'label': 'baas-cli.jar'}
]
```

- [ ] **Step 9: Verify the workflow's shell quoting**

Run: `python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/release.yml'))" && grep -n "sha256sum" .github/workflows/release.yml`
Expected: no YAML error, and the `sha256sum` line present inside the heredoc-style `echo`. The `\${nextRelease.version}` escaping must be preserved — it is a semantic-release template, not a shell variable.

- [ ] **Step 10: Commit**

```bash
git add baas-cli/pom.xml baas-cli/src/main/java/pl/wsztajerowski/baas/BaasVersion.java \
        baas-cli/src/test/java/pl/wsztajerowski/baas/BaasVersionTest.java .github/workflows/release.yml
git commit -m "feat(cli): make the CLI's released version readable at run time

Stamps Implementation-Version into the shaded manifest and rebuilds the
release assets after versions:set, so a published JAR reports its own
version instead of the placeholder. Publishes baas-cli.jar and a runner
checksum as release assets."
```

---

## Task 2: `RunId`, `RunLayout` and the `branch` tag key

**Files:**
- Create: `baas-model/src/main/java/pl/wsztajerowski/baas/model/RunId.java`
- Create: `baas-model/src/main/java/pl/wsztajerowski/baas/model/RunLayout.java`
- Modify: `baas-model/src/main/java/pl/wsztajerowski/baas/model/TagKeys.java:16-27`
- Test: `baas-model/src/test/java/pl/wsztajerowski/baas/model/RunIdTest.java`
- Test: `baas-model/src/test/java/pl/wsztajerowski/baas/model/RunLayoutTest.java`
- Test: `baas-model/src/test/java/pl/wsztajerowski/baas/model/TagKeysTest.java`

**Interfaces:**
- Consumes: `ResultKeys.SEPARATOR`.
- Produces: `RunId.generate()` → `String`; `RunId.generate(Instant)` → `String`; `RunId.LENGTH` → `int` (28); `RunLayout.runPrefix(String project, String runId)` → `String` (no trailing slash); `RunLayout.inputPrefix(String project, String runId)` → `String`; `RunLayout.runnerJarKey(String version)` → `String`; `TagKeys.BRANCH` → `String` (`"branch"`).

- [ ] **Step 1: Write the failing test for `RunId`**

```java
package pl.wsztajerowski.baas.model;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class RunIdTest {

    @Test
    void hasFixedWidth() {
        assertThat(RunId.generate()).hasSize(RunId.LENGTH);
        assertThat(RunId.generate(Instant.parse("2026-01-01T00:00:00Z"))).hasSize(RunId.LENGTH);
    }

    @Test
    void usesAnAlphabetThatCannotCorruptASortKey() {
        String id = RunId.generate();
        assertThat(id).matches("[0-9A-Za-z-]+");
        assertThat(id).doesNotContain(ResultKeys.SEPARATOR).doesNotContain("/");
    }

    @Test
    void lexicographicOrderEqualsChronologicalOrder() {
        List<Instant> chronological = List.of(
            Instant.parse("2025-12-31T23:59:59.999Z"),
            Instant.parse("2026-01-01T00:00:00.000Z"),
            Instant.parse("2026-01-31T23:59:59.999Z"),
            Instant.parse("2026-02-01T00:00:00.000Z"));

        List<String> ids = new ArrayList<>(chronological.stream().map(RunId::generate).toList());
        List<String> sorted = new ArrayList<>(ids);
        sorted.sort(String::compareTo);

        assertThat(sorted).isEqualTo(ids);
    }

    @Test
    void twoIdsFromTheSameInstantDiffer() {
        Instant fixed = Instant.parse("2026-08-20T17:44:32.812Z");
        assertThat(RunId.generate(fixed)).isNotEqualTo(RunId.generate(fixed));
    }

    @Test
    void encodesTheInstantAtMillisecondPrecision() {
        assertThat(RunId.generate(Instant.parse("2026-08-20T17:44:32.812Z")))
            .startsWith("20260820T174432812Z-");
    }

    @Test
    void leavesSortKeyFieldCountUnchanged() {
        String sk = "com.example.Bench" + ResultKeys.SEPARATOR + "method"
            + ResultKeys.SEPARATOR + "thrpt"
            + ResultKeys.SEPARATOR + "2026-08-20T17:44:32.812Z"
            + ResultKeys.SEPARATOR + RunId.generate();
        assertThat(sk.split(ResultKeys.SEPARATOR)).hasSize(5);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -B -pl baas-model test -Dtest=RunIdTest`
Expected: FAIL — compilation error, `RunId` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package pl.wsztajerowski.baas.model;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The only place a run identifier is minted.
 *
 * <p>Time-ordered so an S3 listing reads chronologically — S3 orders lexicographically and offers
 * no sort-by-date for prefixes — and random-suffixed so two runs starting in the same millisecond
 * cannot collide. Nothing parses this value, so the format is a readability convention rather than
 * a contract; the one hard rule is the alphabet, because the id is the last {@code #}-separated
 * field of every sort key and the partition key of {@code requestId-index}.
 */
public final class RunId {

    /** {@code 20260820T174432812Z} (19) + {@code -} (1) + 8 hex (8). */
    public static final int LENGTH = 28;

    private static final DateTimeFormatter INSTANT =
        DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC);

    private static final SecureRandom RANDOM = new SecureRandom();

    private RunId() {}

    public static String generate() {
        return generate(Instant.now());
    }

    public static String generate(Instant instant) {
        byte[] entropy = new byte[4];
        RANDOM.nextBytes(entropy);
        StringBuilder hex = new StringBuilder(8);
        for (byte b : entropy) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return INSTANT.format(instant) + "-" + hex;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -B -pl baas-model test -Dtest=RunIdTest`
Expected: PASS, 6 tests. If `hasFixedWidth` fails, check the arithmetic before changing `LENGTH`: `uuuuMMdd` (8) + `T` (1) + `HHmmssSSS` (9) + `Z` (1) = 19, plus `-` (1) plus 8 hex = 28. A failure here means the format pattern was mistyped, not that the constant is wrong.

- [ ] **Step 5: Pin the width to the format, not to a literal**

Add to `RunIdTest` so a future format edit cannot silently change the width the spec promises:

```java
    @Test
    void theDeclaredLengthMatchesTheFormat() {
        assertThat(RunId.LENGTH).isEqualTo(28);
        assertThat(RunId.generate(Instant.parse("2026-08-20T17:44:32.812Z")))
            .hasSize(RunId.LENGTH);
    }
```

- [ ] **Step 6: Run the test again**

Run: `mvn -B -pl baas-model test -Dtest=RunIdTest`
Expected: PASS, 7 tests.

- [ ] **Step 7: Write the failing test for `RunLayout`**

```java
package pl.wsztajerowski.baas.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RunLayoutTest {

    private static final String ID = "20260820T174432812Z-a3f9c21b";

    @Test
    void runPrefixIsProjectMajor() {
        assertThat(RunLayout.runPrefix("lynx-journal", ID))
            .isEqualTo("runs/lynx-journal/" + ID);
    }

    @Test
    void inputPrefixSitsInsideTheRunPrefix() {
        assertThat(RunLayout.inputPrefix("lynx-journal", ID))
            .isEqualTo("runs/lynx-journal/" + ID + "/input");
    }

    @Test
    void runnerJarLivesOutsideTheRunTree() {
        assertThat(RunLayout.runnerJarKey("1.4.2"))
            .isEqualTo("releases/1.4.2/benchmark-runner.jar");
    }

    @Test
    void neitherPrefixEndsWithASlash() {
        assertThat(RunLayout.runPrefix("p", ID)).doesNotEndWith("/");
        assertThat(RunLayout.inputPrefix("p", ID)).doesNotEndWith("/");
    }

    @Test
    void aBlankProjectIsRejected() {
        assertThatThrownBy(() -> RunLayout.runPrefix("  ", ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("project");
    }
}
```

Add the static import `static org.assertj.core.api.Assertions.assertThatThrownBy;`.

- [ ] **Step 8: Run test to verify it fails**

Run: `mvn -B -pl baas-model test -Dtest=RunLayoutTest`
Expected: FAIL — compilation error, `RunLayout` does not exist.

- [ ] **Step 9: Write minimal implementation**

```java
package pl.wsztajerowski.baas.model;

/**
 * The only place a run's S3 prefix is constructed — the object-store counterpart of
 * {@link ResultKeys}. A hand-built prefix does not fail to compile; it points at nothing, and that
 * presents as an empty download rather than as an error.
 */
public final class RunLayout {

    public static final String RUNS_PREFIX = "runs";
    public static final String RELEASES_PREFIX = "releases";
    public static final String INPUT_SEGMENT = "input";
    public static final String RUNNER_JAR_NAME = "benchmark-runner.jar";

    private RunLayout() {}

    public static String runPrefix(String project, String runId) {
        require(project, "project");
        require(runId, "runId");
        return RUNS_PREFIX + "/" + project + "/" + runId;
    }

    public static String inputPrefix(String project, String runId) {
        return runPrefix(project, runId) + "/" + INPUT_SEGMENT;
    }

    public static String runnerJarKey(String version) {
        require(version, "version");
        return RELEASES_PREFIX + "/" + version + "/" + RUNNER_JAR_NAME;
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A run prefix needs a non-blank " + name + ".");
        }
    }
}
```

- [ ] **Step 10: Run test to verify it passes**

Run: `mvn -B -pl baas-model test -Dtest=RunLayoutTest`
Expected: PASS, 5 tests.

- [ ] **Step 11: Add `BRANCH` to the tag vocabulary**

In `TagKeys.java`, add the constant beside `COMMIT` and include it in `KNOWN`. Leave `MACHINE_OBSERVED` alone — `branch` is caller-supplied like `project` and `commit`:

```java
    public static final String BRANCH = "branch";
```

```java
    public static final Set<String> KNOWN =
        Set.of(PROJECT, TYPE, COMMIT, BRANCH, JDK, CPU_MODEL, CPU_ARCH, INSTANCE_TYPE, IMAGE_VERSION);
```

- [ ] **Step 12: Extend `TagKeysTest`**

Append to `baas-model/src/test/java/pl/wsztajerowski/baas/model/TagKeysTest.java`:

```java
    @Test
    void branchIsKnownButCallerSupplied() {
        assertThat(TagKeys.KNOWN).contains(TagKeys.BRANCH);
        assertThat(TagKeys.MACHINE_OBSERVED).doesNotContain(TagKeys.BRANCH);
    }
```

- [ ] **Step 13: Run the module's whole suite**

Run: `mvn -B -pl baas-model test`
Expected: PASS. `TagKeysTest` may already assert an exact `KNOWN` size — if it fails on a count, update the expected count rather than reverting the constant.

- [ ] **Step 14: Commit**

```bash
git add baas-model/src/main/java/pl/wsztajerowski/baas/model/RunId.java \
        baas-model/src/main/java/pl/wsztajerowski/baas/model/RunLayout.java \
        baas-model/src/main/java/pl/wsztajerowski/baas/model/TagKeys.java \
        baas-model/src/test/java/pl/wsztajerowski/baas/model/ \
        openspec/changes/unified-run-prefix/
git commit -m "feat(model): add RunId and RunLayout, and the branch tag key

One place mints a run identifier and one place builds a run's S3 prefix,
mirroring ResultKeys. The identifier is time-ordered so listings read
chronologically, and random-suffixed so two runs in one millisecond cannot
collide."
```

---

## Task 3: Runner accepts the launcher's instant and refuses an unresolved project

**Files:**
- Modify: `benchmark-runner/src/main/java/pl/wsztajerowski/commands/ApiCommonSharedOptions.java:20-90`
- Test: `benchmark-runner/src/test/java/pl/wsztajerowski/commands/ApiCommonSharedOptionsTest.java` (create if absent)

**Interfaces:**
- Consumes: `RunId.generate()`, `RunLayout.runPrefix(String, String)` from Task 2.
- Produces: `ApiCommonSharedOptions.getCreatedAt()` → `Instant`; `getProject()` → `String` (throws rather than returning `"unknown"`); `getRequestOptions()` unchanged in shape.

- [ ] **Step 1: Build the reactor so this module can be tested at all**

Run: `mvn -B -DskipTests install`
Expected: BUILD SUCCESS. `benchmark-runner`'s tests need the `fake-jmh-benchmarks` and `fake-stress-tests` shaded JARs in the local repository; `mvn -pl benchmark-runner test` alone fails without this.

- [ ] **Step 2: Write the failing test**

```java
package pl.wsztajerowski.commands;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiCommonSharedOptionsTest {

    private static ApiCommonSharedOptions parse(String... args) {
        ApiCommonSharedOptions options = new ApiCommonSharedOptions();
        new CommandLine(options).parseArgs(args);
        return options;
    }

    @Test
    void createdAtIsTheCallerSuppliedInstant() {
        assertThat(parse("--created-at", "2026-08-20T17:44:32.812Z", "--project", "p").getCreatedAt())
            .isEqualTo(Instant.parse("2026-08-20T17:44:32.812Z"));
    }

    @Test
    void createdAtFallsBackToNowSoDirectInvocationKeepsWorking() {
        Instant before = Instant.now();
        Instant resolved = parse("--project", "p").getCreatedAt();
        assertThat(resolved).isBetween(before, Instant.now());
    }

    @Test
    void createdAtIsStableAcrossCallsWithinOneRun() {
        ApiCommonSharedOptions options = parse("--project", "p");
        assertThat(options.getCreatedAt()).isEqualTo(options.getCreatedAt());
    }

    @Test
    void anUnresolvedProjectIsRejectedRatherThanDefaulted() {
        assertThatThrownBy(() -> parse().getProject())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("--project");
    }

    @Test
    void aProjectTagStillResolvesTheProject() {
        assertThat(parse("--tag", "project=lynx-journal").getProject()).isEqualTo("lynx-journal");
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -B -pl benchmark-runner test -Dtest=ApiCommonSharedOptionsTest`
Expected: FAIL — `--created-at` is an unknown option and `getProject()` returns `"unknown"`.

- [ ] **Step 4: Add `--created-at`**

In `ApiCommonSharedOptions`, beside the existing `--request-id` option:

```java
    @Option(names = "--created-at",
        description = "The run's instant, supplied by the launching CLI so the run identifier and the "
            + "stored timestamp cannot disagree. Default: now.")
    String createdAt;

    private Instant resolvedCreatedAt;

    /**
     * Read once and cached. The runner already captures one timestamp per run rather than one per
     * result — a per-result clock read would make two results from the same run differ by a stray
     * millisecond — and this extends that single read one hop out, to the machine that named the run.
     */
    public Instant getCreatedAt() {
        if (resolvedCreatedAt == null) {
            resolvedCreatedAt = (createdAt == null || createdAt.isBlank())
                ? Instant.now()
                : Instant.parse(createdAt);
        }
        return resolvedCreatedAt;
    }
```

- [ ] **Step 5: Replace the `"unknown"` fallback with a hard failure**

Replace the body of `getProject()` and its javadoc:

```java
    /**
     * No fallback. A placeholder project silently writes measurements to a partition nobody queries
     * — CI has been doing exactly that — and under the unified layout it would also scatter S3
     * artifacts under a placeholder prefix.
     */
    public String getProject() {
        if (project != null && !project.isBlank()) {
            return project;
        }
        String tagged = Optional.ofNullable(tags).map(t -> t.get(TagKeys.PROJECT)).orElse(null);
        if (tagged != null && !tagged.isBlank()) {
            return tagged;
        }
        throw new IllegalStateException(
            "Cannot determine the project: pass --project <name> (or --tag project=<name>).");
    }
```

- [ ] **Step 6: Default the request id and result path from the shared code**

Replace the two defaults so the runner and the CLI agree on both shapes:

```java
    public String getRequestId() {
        return (requestId == null || requestId.isBlank()) ? RunId.generate(getCreatedAt()) : requestId;
    }
```

and in `getRequestOptions()`, replace `Optional.ofNullable(resultPath).orElse(Path.of(nonNullRequestId))` with:

```java
        Path nonNullResultPath = Optional.ofNullable(resultPath)
            .orElseGet(() -> Path.of(RunLayout.runPrefix(getProject(), nonNullRequestId)));
```

- [ ] **Step 7: Thread `createdAt` into the stored measurement**

Find where `createdAt` is set on the measurement (`grep -rn "createdAt" benchmark-runner/src/main`) and pass `options.getCreatedAt()` instead of a locally-read `Instant.now()`. The run-level capture in `JmhRunResults` already shares one instant across a run's results; this replaces the source of that instant, not the sharing.

- [ ] **Step 8: Run test to verify it passes**

Run: `mvn -B -pl benchmark-runner test -Dtest=ApiCommonSharedOptionsTest`
Expected: PASS, 5 tests.

- [ ] **Step 9: Fix the tests that asserted the old fallback**

Run: `mvn -B -pl benchmark-runner test`
Expected: some existing tests fail asserting `"unknown"`. Update each to assert the throw instead. Do not reintroduce the fallback to keep them green — the removal is the point.

- [ ] **Step 10: Commit**

```bash
git add benchmark-runner/src
git commit -m "feat(runner): accept --created-at and refuse an unresolved project

The launching CLI now supplies the run's instant, so a run identifier and
its measurements' timestamps cannot disagree. The silent 'unknown' project
fallback is gone: it was writing CI's results to a partition nobody queries."
```

---

## Task 4: `baas run` mints one identifier from one clock read

**Files:**
- Modify: `baas-cli/src/main/java/pl/wsztajerowski/baas/commands/RunCommand.java:210-230,423`
- Modify: `baas-cli/src/main/java/pl/wsztajerowski/baas/commands/GitProject.java:26-29`
- Modify: `baas-cli/src/main/java/pl/wsztajerowski/baas/infra/UserDataScriptBuilder.java:192-196`
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/commands/RunCommandTest.java`

**Interfaces:**
- Consumes: `RunId.generate(Instant)`, `RunLayout.runPrefix`, `RunLayout.inputPrefix`, `TagKeys.BRANCH` from Task 2.
- Produces: `GitProject.fromCommonDir(String commonDir)` → `String` (package-private); `UserDataScriptBuilder.build(...)` gains a `String createdAt` parameter, inserted immediately after `resultPath`, and `MANIFEST_SCHEMA_VERSION` becomes `3`. Task 5 modifies the same method and adds a `String benchmarkJarS3Key` parameter; do Task 4 first.

- [ ] **Step 1: Write the failing test for worktree-aware project derivation**

Add to `RunCommandTest`:

```java
    @Test
    void aLinkedWorktreeResolvesToItsRepository() {
        // `--show-toplevel` returns the worktree directory, so a run launched from
        // .claude/worktrees/ddb-phase3 was attributed to project "ddb-phase3".
        assertThat(GitProject.fromCommonDir("/home/dev/lynx-journal/.git")).isEqualTo("lynx-journal");
        assertThat(GitProject.fromCommonDir("/home/dev/lynx-journal/.git/")).isEqualTo("lynx-journal");
    }

    @Test
    void anOrdinaryCloneIsUnaffected() {
        assertThat(GitProject.fromCommonDir("/home/dev/lynx-journal/.git")).isEqualTo("lynx-journal");
    }

    @Test
    void aBareRepositoryStillYieldsAName() {
        assertThat(GitProject.fromCommonDir("/srv/git/lynx-journal.git")).isEqualTo("lynx-journal");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -B -pl baas-model,baas-cli -am test -Dtest=RunCommandTest`
Expected: FAIL — compilation error, `GitProject.fromCommonDir` does not exist.

- [ ] **Step 3: Implement `fromCommonDir` and switch the git invocation**

In `GitProject.java`, add beside `fromToplevel`:

```java
    /**
     * {@code --git-common-dir} resolves to the main repository's {@code .git} in a linked worktree
     * and is a no-op for an ordinary clone. {@code --show-toplevel} returns the worktree directory,
     * which attributed a run launched from {@code .claude/worktrees/ddb-phase3} to project
     * {@code ddb-phase3}.
     */
    static String fromCommonDir(String commonDir) {
        if (commonDir == null || commonDir.isBlank()) return null;
        String trimmed = commonDir.strip();
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        if (trimmed.endsWith("/.git")) {
            trimmed = trimmed.substring(0, trimmed.length() - "/.git".length());
        } else if (trimmed.endsWith(".git")) {
            trimmed = trimmed.substring(0, trimmed.length() - ".git".length());
            while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return fromToplevel(trimmed);
    }
```

Change `repositoryName` to use it:

```java
    static String repositoryName(Path workingDir) {
        String common = gitOutput(workingDir, "git", "rev-parse", "--path-format=absolute", "--git-common-dir");
        return common != null ? fromCommonDir(common) : fromToplevel(gitOutput(workingDir, "git", "rev-parse", "--show-toplevel"));
    }
```

`--path-format=absolute` matters: without it, `--git-common-dir` can return the relative `.git`, which has no repository name in it. The `--show-toplevel` fallback covers git versions that reject `--path-format`.

In `RunCommand.resolveProject(Path)`, replace the derivation line with `GitProject.repositoryName(workingDir)` and drop the now-unused `projectFromToplevel` indirection only if no other caller uses it — check with `grep -rn "projectFromToplevel" baas-cli/src` first, and keep it if `baas results` still calls it.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -B -pl baas-model,baas-cli -am test -Dtest=RunCommandTest`
Expected: PASS.

- [ ] **Step 5: Verify against real git, both layouts**

Run:
```bash
git rev-parse --path-format=absolute --git-common-dir
git worktree add /tmp/wt-probe -b tmp-probe >/dev/null 2>&1 && \
  (cd /tmp/wt-probe && git rev-parse --path-format=absolute --git-common-dir)
git worktree remove /tmp/wt-probe --force && git branch -D tmp-probe
```
Expected: both print a path ending in `benchmark-as-a-service/.git`. This is `tasks.md` §1.3, and it is blocking — if the worktree case prints the worktree's own `.git`, the approach is wrong and `resolveProject` must instead read the `gitdir:` pointer file.

- [ ] **Step 6: Replace the two identifiers with one**

In `RunCommand.call()`, replace the "4. Generate IDs" block:

```java
        // 4. Generate the run's identity. One clock read: the instant travels into the id, into the
        // S3 prefix and on to the runner as --created-at, so the prefix name and the stored
        // timestamp are the same value rather than two values that happen to be close.
        Instant runInstant = Instant.now();
        String runId = RunId.generate(runInstant);
        String resultPath = RunLayout.runPrefix(resolvedProject, runId);
        logger.info("Run {} — results will land under s3://{}/{}",
            runId, config.getAws().getBucket(), resultPath);
```

Replace the two upload keys:

```java
        String benchmarkJarKey = RunLayout.inputPrefix(resolvedProject, runId) + "/benchmark.jar";
```

and, in the `--runner-jar` branch:

```java
            runnerJarS3Key = RunLayout.inputPrefix(resolvedProject, runId) + "/runner.jar";
```

Every later reference to `requestId` becomes `runId`. `resolvedProject` must be resolved *before* this block — move the `resolveProject()` call above it if it currently follows.

- [ ] **Step 7: Add the `branch` tag and pass the instant to the builder**

In `buildRunnerTags`, add `branch` alongside project and commit, caller-overridable (do **not** add it to `MACHINE_OBSERVED`):

```java
        putIfAbsent(tags, TagKeys.BRANCH, resolvedBranch);
```

Extend the `UserDataScriptBuilder.build(...)` call and signature with `String createdAt` immediately after `resultPath`, passing `runInstant.toString()`.

- [ ] **Step 8: Thread `--created-at` through the script body**

In `UserDataScriptBuilder`, add the export line beside the other exports and the flag to the runner invocation. Capture into a shell variable first, per the manifest invariant:

```java
            "export CREATED_AT='" + createdAt + "'\n" +
```

and in the `timeout ... java -jar` block, after `--request-id`:

```
          --created-at     "${CREATED_AT}" \\
```

- [ ] **Step 9: Add the run's identity to the environment manifest**

`environment.json` is written *before* the benchmark, so it is what a run that dies early leaves behind — and it is what buys back the self-description given up by making the id opaque. In the `MANIFEST` heredoc (`UserDataScriptBuilder.java:83-92`), add four fields beside `schemaVersion`:

```
          "project": "${PROJECT}",
          "branch": "${BRANCH}",
          "requestId": "${REQUEST_ID}",
          "createdAt": "${CREATED_AT}",
```

Add `export PROJECT=` and `export BRANCH=` beside the other exports, both through `json_escape` — a branch name can contain characters that would break the JSON string, and the heredoc body must contain nothing but `${VAR}` references. `REQUEST_ID` and `CREATED_AT` are already exported.

Bump `MANIFEST_SCHEMA_VERSION` from 2 to 3 (`UserDataScriptBuilder.java:11`) — the shape changed, and `baas env diff` reads this file.

- [ ] **Step 10: Assert the manifest carries the identity**

Add to `EnvironmentManifestTest` (or `UserDataScriptBuilderTest`, wherever the manifest is currently asserted):

```java
    @Test
    void theManifestIdentifiesARunThatStoredNothing() {
        String script = render();
        assertThat(script).contains("\"project\": \"${PROJECT}\"")
                          .contains("\"branch\": \"${BRANCH}\"")
                          .contains("\"requestId\": \"${REQUEST_ID}\"")
                          .contains("\"createdAt\": \"${CREATED_AT}\"");
        assertThat(script).contains("PROJECT=$(json_escape")
                          .contains("BRANCH=$(json_escape");
    }
```

- [ ] **Step 11: Assert the rendered script carries it**

Add to `UserDataScriptBuilderTest`:

```java
    @Test
    void theRunnerReceivesTheLaunchersInstant() {
        String script = render();  // use the test's existing render helper
        assertThat(script).contains("export CREATED_AT='");
        assertThat(script).contains("--created-at     \"${CREATED_AT}\"");
    }
```

- [ ] **Step 12: Run the CLI suite**

Run: `mvn -B -pl baas-model,baas-cli -am test`
Expected: PASS. Tests asserting the old `<branch>/<type>/<timestamp>` result path or `<type>-<timestamp>` request id will fail — update them to the new shapes rather than reverting.

- [ ] **Step 13: Commit**

```bash
git add baas-cli/src baas-model/src
git commit -m "feat(cli): give a run one identifier, one prefix and one instant

baas run reads the clock once, mints a RunId from it, derives the run's S3
prefix from RunLayout and passes the same instant to the runner. Project
derivation now resolves the main repository, so a run launched from a linked
worktree is no longer attributed to the worktree directory."
```

---

## Task 5: The instance stops calling GitHub; the CLI seeds a checksum-verified runner JAR

**Files:**
- Modify: `baas-cli/src/main/java/pl/wsztajerowski/baas/infra/UserDataScriptBuilder.java:117-127`
- Create: `baas-cli/src/main/java/pl/wsztajerowski/baas/infra/RunnerJarResolver.java`
- Modify: `baas-cli/src/main/java/pl/wsztajerowski/baas/commands/RunCommand.java` (early in `call()`)
- Modify: `baas-cli/src/main/java/pl/wsztajerowski/baas/config/BaasConfig.java` (add `runnerSourceRepo`)
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/infra/RunnerJarResolverTest.java`
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/infra/UserDataScriptBuilderTest.java`

**Interfaces:**
- Consumes: `BaasVersion.current()`/`isReleased()` (Task 1), `RunLayout.runnerJarKey(String)` (Task 2).
- Produces: `RunnerJarResolver.resolve(S3Client s3, String bucket, String version, String sourceRepo)` → `String` (the S3 key); `RunnerJarResolver.verify(byte[] jar, String publishedSha256)` → `void` (throws `IllegalStateException` on mismatch); `RunnerJarResolver.sha256Hex(byte[])` → `String` (lowercase hex). Also adds a `String benchmarkJarS3Key` parameter to `UserDataScriptBuilder.build(...)`.

- [ ] **Step 1: Write the failing test for checksum verification**

```java
package pl.wsztajerowski.baas.infra;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunnerJarResolverTest {

    private static final byte[] PAYLOAD = "pretend-jar".getBytes(StandardCharsets.UTF_8);
    // echo -n 'pretend-jar' | sha256sum
    private static final String SHA = "f2d5a3b7a1c9e0d4b6f8c2a1e3d5b7f9a0c2e4d6b8f0a2c4e6d8b0f2a4c6e8d0";

    @Test
    void aMismatchIsAHardFailure() {
        assertThatThrownBy(() -> RunnerJarResolver.verify(PAYLOAD, "0".repeat(64)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("checksum");
    }

    @Test
    void aBlankChecksumIsAFailureNotASkip() {
        assertThatThrownBy(() -> RunnerJarResolver.verify(PAYLOAD, "  "))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("checksum");
    }

    @Test
    void aMatchingChecksumPasses() {
        assertThatCode(() -> RunnerJarResolver.verify(PAYLOAD, RunnerJarResolver.sha256Hex(PAYLOAD)))
            .doesNotThrowAnyException();
    }

    @Test
    void theChecksumComparisonIgnoresCaseAndSurroundingWhitespace() {
        String upper = RunnerJarResolver.sha256Hex(PAYLOAD).toUpperCase();
        assertThatCode(() -> RunnerJarResolver.verify(PAYLOAD, "  " + upper + "  \n"))
            .doesNotThrowAnyException();
    }
}
```

Delete the unused `SHA` constant if the compiler warns; it is documentation of how to regenerate one by hand.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -B -pl baas-model,baas-cli -am test -Dtest=RunnerJarResolverTest`
Expected: FAIL — compilation error, `RunnerJarResolver` does not exist.

- [ ] **Step 3: Write the verification half**

```java
package pl.wsztajerowski.baas.infra;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Seeds and resolves the version-pinned runner JAR.
 *
 * <p>The download moved from the EC2 instance to the laptop, which is what makes verification
 * possible at all — CLAUDE.md carried "runner JAR downloaded without checksum verification" as an
 * accepted risk precisely because a throwaway instance mid-boot had nothing to verify against.
 */
public final class RunnerJarResolver {

    private RunnerJarResolver() {}

    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static void verify(byte[] jar, String publishedSha256) {
        if (publishedSha256 == null || publishedSha256.isBlank()) {
            throw new IllegalStateException(
                "No published checksum for the runner JAR — refusing to upload it unverified.");
        }
        String expected = publishedSha256.strip().toLowerCase();
        String actual = sha256Hex(jar);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                "Runner JAR checksum mismatch: expected " + expected + ", got " + actual
                    + ". Nothing was uploaded.");
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -B -pl baas-model,baas-cli -am test -Dtest=RunnerJarResolverTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Add the seeding half**

Append to `RunnerJarResolver`:

```java
    /**
     * Upload-if-absent, never overwrite. A present object is used as-is, so a corrupted one does not
     * self-repair — the fix is deleting the key so the next run re-seeds it.
     */
    public static String resolve(S3Client s3, String bucket, String version, String sourceRepo) {
        String key = RunLayout.runnerJarKey(version);
        if (exists(s3, bucket, key)) {
            return key;
        }
        byte[] jar = fetchAsset(sourceRepo, version, "benchmark-runner.jar");
        String sha = new String(fetchAsset(sourceRepo, version, "benchmark-runner.jar.sha256"),
            StandardCharsets.UTF_8);
        verify(jar, sha);
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
            RequestBody.fromBytes(jar));
        return key;
    }
```

Implement `exists` with `headObject` catching `NoSuchKeyException`, and `fetchAsset` with `java.net.http.HttpClient` against
`https://github.com/<sourceRepo>/releases/download/v<version>/<assetName>`, following redirects. On any non-200, throw naming the repository, version and asset — that is finding **A7**'s "fail loudly on an empty URL" half.

- [ ] **Step 6: Make the source repository configuration**

Add a `runnerSourceRepo` field to `BaasConfig.AwsConfig`'s sibling section (or a new top-level `runner` block, matching the file's existing style), defaulting to `wsztajerowski/benchmark-as-a-service`. Confirm `ConfigService` round-trips it: `mvn -B -pl baas-model,baas-cli -am test -Dtest=BaasConfigYamlTest`.

- [ ] **Step 7: Delete the GitHub branch from user-data**

In `UserDataScriptBuilder`, replace the whole `if [[ -n "${RUNNER_JAR_S3_KEY}" ]] … else … curl … wget … fi` block with an unconditional copy:

```java
        "        aws s3 cp \"s3://${S3_BUCKET}/${RUNNER_JAR_S3_KEY}\" /app/benchmark-runner.jar\n" +
```

`RUNNER_JAR_S3_KEY` is now always set — either the `releases/<version>/` key or, with `--runner-jar`, the run's `input/runner.jar`.

Also update the benchmark-JAR copy, which still hardcodes the old layout:

```java
        "        aws s3 cp \"s3://${S3_BUCKET}/${BENCHMARK_JAR_S3_KEY}\" /app/benchmark-under-test.jar\n" +
```

adding `BENCHMARK_JAR_S3_KEY` as an exported variable and a `build(...)` parameter rather than reconstructing `runs/${REQUEST_ID}/benchmark.jar` in the script.

- [ ] **Step 8: Assert the script reaches no external host**

Add to `UserDataScriptBuilderTest`:

```java
    @Test
    void theInstanceNeverContactsGitHubForItsRunner() {
        String script = render();
        assertThat(script).doesNotContain("api.github.com")
                          .doesNotContain("releases/latest")
                          .doesNotContain("wget");
        assertThat(script).containsOnlyOnce("/app/benchmark-runner.jar");
    }
```

- [ ] **Step 9: Hard-fail a reactor build without `--runner-jar`**

Early in `RunCommand.call()` — before the Maven build and before any upload:

```java
        if (runnerJar == null && !BaasVersion.isReleased()) {
            logger.error("""
                This is an unreleased build ({}), so there is no runner release to pin to.
                Pass --runner-jar <path> to run against a local build.""", BaasVersion.current());
            return 1;
        }
```

Add a test asserting the exit code is 1 and that no S3 or EC2 client was constructed. Model it on the existing `RunCommandTest` cases that assert "no EC2 instance is launched".

- [ ] **Step 10: Run the CLI suite**

Run: `mvn -B -pl baas-model,baas-cli -am test`
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add baas-cli/src
git commit -m "feat(cli): pin the runner JAR to the CLI's version and verify it

The instance no longer calls api.github.com or resolves releases/latest; it
copies a version-pinned JAR from the bucket. The CLI seeds that slot from the
release matching its own version, verified against a published sha256, and
refuses to launch from an unreleased build without --runner-jar.

Closes A7."
```

---

## Task 6: `baas download` accepts a run identifier; the results column stops truncating

**Files:**
- Modify: `baas-cli/src/main/java/pl/wsztajerowski/baas/commands/DownloadCommand.java:36-58`
- Modify: `baas-cli/src/main/java/pl/wsztajerowski/baas/results/ResultsQueryService.java:100-109`
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/results/ResultsFormatTest.java`
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/S3DownloadIT.java`

**Interfaces:**
- Consumes: `RunId.LENGTH` (Task 2), `ResultKeys.REQUEST_ID_INDEX_NAME`.
- Produces: `DownloadCommand.looksLikeRunId(String)` → `boolean` (package-private, for testing); `ResultsQueryService.resultPathForRun(String runId)` → `String` (the stored `resultPath`, or `null` when the index holds no such run).

- [ ] **Step 1: Write the failing test for argument discrimination**

Add a new test class `baas-cli/src/test/java/pl/wsztajerowski/baas/commands/DownloadArgumentTest.java`:

```java
package pl.wsztajerowski.baas.commands;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DownloadArgumentTest {

    @Test
    void aRunIdentifierIsRecognised() {
        assertThat(DownloadCommand.looksLikeRunId("20260820T174432812Z-a3f9c21b")).isTrue();
    }

    @Test
    void anOldLayoutPathIsNotARunIdentifier() {
        assertThat(DownloadCommand.looksLikeRunId("main/jmh/20260819_090000")).isFalse();
    }

    @Test
    void aNewLayoutPathIsNotARunIdentifier() {
        assertThat(DownloadCommand.looksLikeRunId("runs/lynx-journal/20260820T174432812Z-a3f9c21b"))
            .isFalse();
    }

    @Test
    void aLegacyRequestIdIsNotMistakenForOne() {
        assertThat(DownloadCommand.looksLikeRunId("jmh-20260819_090000")).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -B -pl baas-model,baas-cli -am test -Dtest=DownloadArgumentTest`
Expected: FAIL — compilation error, `looksLikeRunId` does not exist.

- [ ] **Step 3: Implement the discrimination**

In `DownloadCommand`:

```java
    private static final Pattern RUN_ID =
        Pattern.compile("\\d{8}T\\d{9}Z-[0-9a-f]{8}");

    /**
     * A path is never a run identifier and a run identifier never contains a slash, so the two
     * argument shapes cannot be confused. The path branch is what keeps every run stored before
     * this layout retrievable.
     */
    static boolean looksLikeRunId(String argument) {
        return argument != null && RUN_ID.matcher(argument).matches();
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -B -pl baas-model,baas-cli -am test -Dtest=DownloadArgumentTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Resolve an identifier through the index**

Rename the parameter's `paramLabel` to `<runId|resultPath>` and update its description. In `call()`, before computing `prefix`:

```java
        String resolvedPath = resultPath;
        if (looksLikeRunId(resultPath)) {
            resolvedPath = new ResultsQueryService(factory).resultPathForRun(resultPath);
            if (resolvedPath == null) {
                logger.error("No run found with id {}.", resultPath);
                return 1;
            }
            logger.debug("Run {} resolves to {}", resultPath, resolvedPath);
        }
        String prefix = resolvedPath.endsWith("/") ? resolvedPath : resolvedPath + "/";
```

Add `resultPathForRun` to `ResultsQueryService`: a `Query` on `ResultKeys.REQUEST_ID_INDEX_NAME` with `gsi1pk = <runId>`, `Limit(1)`, returning the first item's `resultPath` or `null`. The index is `ProjectionType: ALL`, so no follow-up `GetItem` is needed.

- [ ] **Step 6: Widen the results column**

In `ResultsQueryService.printTable`, change the format string and drop the truncation:

```java
        String fmt = "%-45s %-28s %-14s %-8s %14s %12s %-10s%n";
```

```java
                r.requestId(),
```

and widen the rule: `System.out.println("-".repeat(141));`

- [ ] **Step 7: Assert the identifier renders whole**

Add to `ResultsFormatTest`:

```java
    @Test
    void theRunIdentifierIsNotTruncated() {
        String id = "20260820T174432812Z-a3f9c21b";
        String out = captureTable(List.of(rowWithRequestId(id)));
        assertThat(out).contains(id);
    }
```

Use the class's existing capture and row-fixture helpers; if none exist, follow the pattern in `ResultsGroupingTest`.

- [ ] **Step 8: Run the CLI suite**

Run: `mvn -B -pl baas-model,baas-cli -am test`
Expected: PASS. Column-width assertions elsewhere may need the new widths.

- [ ] **Step 9: Commit**

```bash
git add baas-cli/src
git commit -m "feat(cli): download by run id, and stop truncating it in results

The id is what baas run prints, so it is what a user has in hand. A literal
result path is still accepted, which is what keeps runs stored before this
layout retrievable. Fixed-width ids let the results column render whole —
truncation at 17 landed on the shared prefix and made distinct rows identical."
```

---

## Task 7: Infrastructure — suspended versioning, no run-prefix expiry, no dead CI grant

**Files:**
- Modify: `infra/cf-template-core.yaml:183-205`
- Modify: `infra/cf-template-ci.yaml:112-118`
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/CoreTemplateTest.java`
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/CiTemplateTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Write the failing template tests**

Add to `CoreTemplateTest`:

```java
    @Test
    void bucketVersioningIsSuspended() {
        assertThat(template()).contains("Status: Suspended");
    }

    @Test
    void noLifecycleRuleExpiresRunArtifacts() {
        // The rule's own premise — everything under runs/ is re-creatable from source — is exactly
        // what the unified layout falsifies: the uploaded JAR is the only copy of what a
        // measurement actually ran.
        assertThat(template()).doesNotContain("expire-uploaded-benchmark-jars");
        assertThat(template()).doesNotContain("ExpirationInDays");
    }

    @Test
    void noncurrentRulesSurviveSuspension() {
        assertThat(template()).contains("expire-noncurrent-versions")
                              .contains("expire-orphaned-delete-markers")
                              .contains("abort-incomplete-uploads");
    }
```

Add to `CiTemplateTest`:

```java
    @Test
    void theWorkflowRoleHasNoGrantForTheRetiredCiPrefix() {
        assertThat(template()).doesNotContain("/ci/*");
    }
```

Use each class's existing template-loading helper rather than re-reading the file.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -B -pl baas-model,baas-cli -am test -Dtest='CoreTemplateTest+CiTemplateTest'`
Expected: FAIL on all four new tests.

- [ ] **Step 3: Edit the core template**

In `infra/cf-template-core.yaml`, change the versioning status and delete the last rule:

```yaml
      VersioningConfiguration:
        # Suspended: results are write-once and run ids carry 32 bits of entropy, so an overwrite
        # is not a failure mode worth retaining versions for. The noncurrent rules below stay —
        # versions written before suspension persist until they are reaped.
        Status: Suspended
```

Delete the `expire-uploaded-benchmark-jars` rule and the two comment lines above it. Keep `expire-noncurrent-versions`, `expire-orphaned-delete-markers` and `abort-incomplete-uploads` exactly as they are.

**Do not touch `RunnerSecurityGroup`'s `GroupDescription` while in this file** — it is an immutable property, so editing it replaces the security group and changes its id, which `~/.baas/config.yaml` and any in-flight run still reference.

- [ ] **Step 4: Edit the CI template**

In `infra/cf-template-ci.yaml`, delete the `ci/*` line from the S3 policy's `Resource` list, leaving the `runs/*` entry.

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn -B -pl baas-model,baas-cli -am test -Dtest='CoreTemplateTest+CiTemplateTest'`
Expected: PASS.

- [ ] **Step 6: Add the suspended-bucket emptying test**

The bucket keeps versions written before suspension, so `deleteAllObjects` must keep walking them. Add to `S3UploadServiceIT` (LocalStack-backed):

```java
    @Test
    void emptyingWalksVersionsWrittenBeforeSuspension() {
        // enable versioning, write a key twice, suspend versioning, write once more
        // then: deleteAllObjects, and assert listObjectVersions returns nothing
    }
```

Implement the four steps with the class's existing S3 client and bucket fixture. This is the one place the behaviour is observable; a unit test cannot see it.

- [ ] **Step 7: Run the integration test**

Run: `mvn -B -pl baas-model,baas-cli -am verify -Dtest=S3UploadServiceIT -DfailIfNoTests=false`
Expected: PASS. Requires Docker for LocalStack.

- [ ] **Step 8: Confirm the deployer policy still fits**

Run: `mvn -B -pl baas-model,baas-cli -am test -Dtest=DeployerPolicyTest`
Expected: PASS, including `renderedPolicyLeavesRoomInAnInlinePolicyBudget`. This task adds no IAM, so the number should not have moved — if it did, something else changed.

- [ ] **Step 9: Commit**

```bash
git add infra/cf-template-core.yaml infra/cf-template-ci.yaml baas-cli/src/test
git commit -m "feat(infra): suspend bucket versioning and stop expiring run artifacts

The runs/ expiry rule assumed everything under that prefix was re-creatable
from source; the unified layout makes the uploaded JAR the only record of what
a measurement ran. Versioning is suspended because the entropy in a run id
removes the overwrite it was guarding against. The CI role's ci/* grant is
dead and removed."
```

---

## Task 8: CI — two jobs, two runs, two prefixes

**Files:**
- Modify: `.github/workflows/e2e-cloud-test.yml:20-35,90-125`

**Interfaces:**
- Consumes: the CLI surface from Tasks 4-6.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Read the current shape**

Run: `sed -n '20,35p;88,125p' .github/workflows/e2e-cloud-test.yml`
Expected: a single `ID=CI_E2E_$(date -u …)` and `result_path=ci/$ID` consumed by two benchmark jobs — so both write `run-status` to one key.

- [ ] **Step 2: Mint one id per benchmark job**

Replace the shared-id step in each benchmark job with a per-job one. Both values come from one `date` call so the id and `--created-at` cannot disagree:

```yaml
      - name: Mint run identity
        id: run
        run: |
          INSTANT=$(date -u +'%Y-%m-%dT%H:%M:%S.%3NZ')
          COMPACT=$(date -u -d "$INSTANT" +'%Y%m%dT%H%M%S%3NZ')
          RUN_ID="${COMPACT}-$(openssl rand -hex 4)"
          echo "run_id=$RUN_ID"     >> "$GITHUB_OUTPUT"
          echo "created_at=$INSTANT" >> "$GITHUB_OUTPUT"
```

- [ ] **Step 3: Pass the identity and an explicit project**

Where each job invokes the runner, pass `--request-id`, `--created-at` and `--project`. CI has been writing `RESULT#unknown`; after Task 3 an unresolved project is a hard failure, so omitting `--project` would break the workflow rather than degrade quietly.

- [ ] **Step 4: Remove the retired prefix**

Delete every `ci/` construction. The result path now comes from the runner's own default, `runs/<project>/<runId>`.

- [ ] **Step 5: Validate the workflow parses**

Run: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/e2e-cloud-test.yml'))" && echo OK`
Expected: `OK`.

- [ ] **Step 6: Verify `date -u -d` availability**

Run: `date -u -d "2026-08-20T17:44:32.812Z" +'%Y%m%dT%H%M%S%3NZ'`
Expected: `20260820T174432812Z` on the GitHub runner's GNU coreutils. On macOS this fails — that is expected and fine, the snippet runs only on `ubuntu-latest`. If you need to check locally, use `gdate`.

- [ ] **Step 7: Do not touch the other workflow**

Run: `git diff --name-only .github/workflows/`
Expected: `e2e-cloud-test.yml` and, from Task 1, `release.yml` — and **not** `exec-single-benchmark.yml` or `benchmark-runner.yml`. Those belong to `gha-workflow-migration-to-dynamodb`; coordinate merge order with that change before landing this one.

- [ ] **Step 8: Commit**

```bash
git add .github/workflows/e2e-cloud-test.yml
git commit -m "feat(ci): give each benchmark job its own run

Two jobs were sharing one result path, so both wrote run-status to the same
key. Each now mints its own id from a single instant it also passes as
--created-at, and passes --project explicitly — CI has been writing
RESULT#unknown, which the runner now rejects."
```

---

## Task 9: Documentation and review findings

**Files:**
- Modify: `CLAUDE.md` (S3 result layout, Invariants, Accepted risks, What isn't there)
- Modify: `docs/review/baas-cli-findings.md:37-40,202-226`
- Modify: `docs/review/benchmark-runner-findings.md`
- Modify: `docs/diagrams/*.mmd`, `infra/README.md`

**Interfaces:**
- Consumes: the behaviour landed in Tasks 1-8.
- Produces: nothing.

- [ ] **Step 1: Rewrite the S3 result layout section**

In `CLAUDE.md`, replace `<result-path> = <branch>/<type>/<YYYYMMDD_HHMMSS>` with `runs/<project>/<runId>/`, add the `input/` row and the `releases/<version>/` row to the key table, and delete the `runs/<requestId>/` row describing the old separate uploads prefix.

- [ ] **Step 2: Update the invariants the change moved**

Replace the "S3 upload paths are request-ID-scoped" invariant with the unified one, and delete the sentence in the user-data invariants describing the GitHub download. Add four invariants:

- `baas run` reads the clock once per run; the id's instant and the stored `createdAt` are the same value, and the instance's clock never reaches the record.
- The instance never contacts GitHub. Its only runner-JAR source is `releases/<version>/` in the bucket; restoring a network fetch reintroduces both the drift and the egress `private-runner-network` exists to remove.
- `releases/<version>/benchmark-runner.jar` is seeded once and never overwritten. A corrupted object does not self-repair — delete the key and the next run re-seeds it.
- Bucket versioning is `Suspended` deliberately, and no lifecycle rule expires current objects under `runs/`. Both are load-bearing: the expiry rule would delete results, and versioning was only guarding an overwrite the run id now prevents.

- [ ] **Step 3: Move the runner-JAR risk out of Accepted risks**

Delete the *Runner JAR integrity* row and state in its place what closed it: the CLI verifies the downloaded asset against a `.sha256` published by the same release build. Do not delete the row silently — an accepted risk that vanishes reads as an oversight.

- [ ] **Step 4: Mark A7 and A9 Fixed**

In `docs/review/baas-cli-findings.md`, update rows 9 and 11 of the status table to **Fixed**, and append to each finding's section a sentence naming how: A7 by deleting the instance's GitHub call entirely and making the source repository a config key; A9 by the run id's 32-bit entropy suffix, which closes the collision rather than narrowing it.

- [ ] **Step 5: File the `@Param` collision**

Append a new finding to `docs/review/benchmark-runner-findings.md`, adding a row to its status table:

> `JmhResult` does not parse JMH's `params` object, and the sort key is `class#method#mode#timestamp#requestId` with one timestamp shared across a run. Two `@Param` variants of the same benchmark method therefore produce an identical sort key, and the second `PutItem` silently overwrites the first. Live today; predates this change. **Proposed fix:** fold the resolved params into the sort key, or store them as a tag and add them to the key.

Severity Med, status Open. This is pre-existing and deliberately not fixed here.

- [ ] **Step 6: Update the diagrams and the infra README**

Run: `grep -rln "runs/\|result-path\|releases/latest" docs/diagrams/ infra/README.md`
Expected: a short list. Update each `.mmd` whose sequence changed — there are no checked-in SVGs, so the source is the deliverable — and any bucket description in `infra/README.md` naming a retired prefix.

- [ ] **Step 7: Verify no stale references remain**

Run: `grep -rn "releases/latest\|api.github.com\|<branch>/<type>/" CLAUDE.md docs/ infra/ baas-cli/src baas-model/src benchmark-runner/src`
Expected: matches only inside `docs/review/` historical finding text and `openspec/changes/archive/`, which describe the past on purpose. Any match in `CLAUDE.md`, `infra/` or live source is a miss.

- [ ] **Step 8: Commit**

```bash
git add CLAUDE.md docs/ infra/README.md
git commit -m "docs: record the unified run layout and close A7/A9

Rewrites the S3 layout section, replaces the request-ID-scoped upload
invariant with the unified one, and adds the four invariants this change
creates. Moves runner-JAR integrity out of Accepted risks — the checksum
closes it — and files the pre-existing @Param sort-key collision."
```

---

## Task 10: Migrate history (only after Tasks 1-9 are deployed and Task 11 has passed once)

**Do not start this task until a real run has been launched and verified on the new layout.** The order is cutover, then migrate — old runs stay resolvable the whole time because `baas download` follows each item's stored `resultPath` rather than reconstructing it, so one idempotent pass at the end suffices.

**Files:**
- Create: `scripts/migrate-run-layout.sh`
- Delete: `scripts/migrate-run-layout.sh` (Step 8)

**Interfaces:**
- Consumes: `RunLayout.runPrefix` semantics (reimplemented in shell — the script is throwaway and must run without a build).
- Produces: nothing. Nothing in CI invokes `scripts/`.

- [ ] **Step 1: Take the inventory the dry run is checked against**

Run:
```bash
TABLE=baas-<prefix>-results
aws dynamodb scan --table-name "$TABLE" \
  --projection-expression "pk,sk,requestId,resultPath" --output json > /tmp/baas-inventory.json
python3 - <<'PY'
import json, collections
items = json.load(open('/tmp/baas-inventory.json'))['Items']
print('items:', len(items))
print('projects:', collections.Counter(i['pk']['S'] for i in items))
print('runs:', len({i['requestId']['S'] for i in items}))
print('unknown items:', sum(1 for i in items if i['pk']['S'] == 'RESULT#unknown'))
print('already migrated:', sum(1 for i in items if i.get('resultPath',{}).get('S','').startswith('runs/')))
PY
```
Expected: counts matching `tasks.md` §1.6. Record them — Step 5 compares against these.

- [ ] **Step 2: Write the script, dry-run first**

```bash
#!/usr/bin/env bash
# Throwaway. Relocates pre-unified-layout runs into runs/<project>/<requestId>/.
# Deleted once it has run; git log is the archive. See openspec/changes/unified-run-prefix/.
set -euo pipefail   # a half-finished migration is worse than an aborted one; there is no
                    # paid instance to orphan here, so the user-data reasoning does not apply

BUCKET="${1:?bucket}"; TABLE="${2:?table}"; DRY="${3:-1}"

aws dynamodb scan --table-name "$TABLE" \
  --projection-expression "pk,sk,requestId,resultPath,resultJsonKey,environmentJsonKey" \
  --output json | python3 -c '
import json, subprocess, sys, os
bucket, table, dry = sys.argv[1], sys.argv[2], sys.argv[3] == "1"
for item in json.load(sys.stdin)["Items"]:
    old = item.get("resultPath", {}).get("S")
    if not old or old.startswith("runs/"):
        continue                               # idempotent: already relocated
    project = item["pk"]["S"].removeprefix("RESULT#")
    new = f"runs/{project}/{item[\"requestId\"][\"S\"]}"
    print(("DRY " if dry else "RUN ") + f"{old} -> {new}")
    if dry:
        continue
    subprocess.run(["aws","s3","cp",f"s3://{bucket}/{old}/",f"s3://{bucket}/{new}/",
                    "--recursive","--only-show-errors"], check=True)
    subprocess.run(["aws","dynamodb","update-item","--table-name",table,
        "--key",json.dumps({"pk":item["pk"],"sk":item["sk"]}),
        "--update-expression","SET resultPath = :p, resultJsonKey = :j, environmentJsonKey = :e",
        "--expression-attribute-values",json.dumps({
            ":p":{"S":new},
            ":j":{"S":item.get("resultJsonKey",{}).get("S","").replace(old,new,1)},
            ":e":{"S":item.get("environmentJsonKey",{}).get("S","").replace(old,new,1)}})],
        check=True)
' "$BUCKET" "$TABLE" "$DRY"
```

Note what this deliberately does not do: it never writes `pk` or `sk`, and it never mints a new identifier. `RESULT#unknown` items are relocated under `runs/unknown/` by the same rule as every other project — re-attribution would mean `DeleteItem` + `PutItem` per row, because `pk` is part of the key.

- [ ] **Step 3: Dry-run and read the output by eye**

Run: `bash scripts/migrate-run-layout.sh baas-<prefix> baas-<prefix>-results 1 | tee /tmp/baas-dryrun.txt && wc -l /tmp/baas-dryrun.txt`
Expected: one `DRY` line per item with a non-`runs/` path, and the distinct target count matching Step 1's run count.

- [ ] **Step 4: Confirm idempotence before touching anything**

Run: `grep -c "^DRY runs/" /tmp/baas-dryrun.txt || true`
Expected: `0` — nothing already under `runs/` is proposed for relocation. If this is non-zero the skip condition is wrong; fix it before Step 5.

- [ ] **Step 5: Run it for real**

Run: `bash scripts/migrate-run-layout.sh baas-<prefix> baas-<prefix>-results 0`
Expected: one `RUN` line per relocated run, no non-zero exit. Then re-run Step 1's inventory: `already migrated` should equal the total item count.

- [ ] **Step 6: Verify a relocated run end to end**

Run:
```bash
baas download <a requestId from before the cutover> -o /tmp/relocated
ls /tmp/relocated
baas results --request-id <that same requestId>
```
Expected: the download contains the run's result JSON, `environment.json` and process output; the results row renders. Also confirm the profiler-artifact subdirectory came across if that run had one.

- [ ] **Step 7: Confirm the `unknown` rows landed correctly**

Run: `aws s3 ls "s3://baas-<prefix>/runs/unknown/" | head`
Expected: prefixes present, and their items' `pk` still `RESULT#unknown` — the migration relocates files, it does not re-attribute measurements.

- [ ] **Step 8: Delete the script**

```bash
git rm scripts/migrate-run-layout.sh
git commit -m "chore: remove the run-layout migration script after running it

Relocated <N> runs across <M> projects into runs/<project>/<requestId>/,
rewriting resultPath, resultJsonKey and environmentJsonKey. No partition key
or sort key was modified and no identifier was reminted. git log is the
archive."
```

Fill in the real `<N>` and `<M>` from Step 5.

---

## Task 11: End-to-end verification — MANUAL

No automated test drives `baas run`. `e2e-cloud-test.yml` exercises the GHA path, which installs its own async-profiler and never boots the runner AMI, so CI cannot catch a bad bake or a broken run path. Everything below is done by hand, against a real account, and costs real money.

**Files:** none.

- [ ] **Step 1: Full reactor build with async coverage**

Run: `ASYNC_PATH=/path/to/libasyncProfiler.so mvn -B clean verify`
Expected: BUILD SUCCESS. Without `ASYNC_PATH`, `JmhWithAsyncProfilerSubcommandServiceIT` silently skips — a green build proves nothing about the profiler path.

- [ ] **Step 2: Apply the infrastructure**

Run: `baas admin setup`
Then: `aws s3api get-bucket-versioning --bucket baas-<prefix>` and `aws s3api get-bucket-lifecycle-configuration --bucket baas-<prefix>`
Expected: `"Status": "Suspended"`, and no rule with `Prefix: runs/` or any `Expiration` on current objects.

- [ ] **Step 3: A real run on the new layout**

Run: `baas run jmh -- <FakeBenchmark> -f 1 -wi 1 -i 3`
Expected: the CLI prints a run id of the form `20260820T174432812Z-a3f9c21b`. Then:
```bash
aws s3 ls --recursive "s3://baas-<prefix>/runs/<project>/<runId>/"
```
Expected: `input/benchmark.jar`, `environment.json`, `packages.txt`, `jmh-output.txt`, `jmh-result.json`, `cloud-init-output.log`, `run-status` — all under the one prefix, and nothing for this run anywhere else in the bucket.

- [ ] **Step 4: The instant is one value, not two**

Run:
```bash
aws s3 cp "s3://baas-<prefix>/runs/<project>/<runId>/environment.json" - | python3 -m json.tool | grep -E 'createdAt|requestId|project|branch'
baas results --request-id <runId> --format json | python3 -c "import json,sys; print(json.load(sys.stdin)[0]['createdAt'])"
```
Expected: the manifest carries all four fields, and the stored `createdAt` equals the instant encoded in the run id — `20260820T174432812Z` ⇒ `2026-08-20T17:44:32.812Z`.

- [ ] **Step 5: The instance never called GitHub**

Run: `aws s3 cp "s3://baas-<prefix>/runs/<project>/<runId>/cloud-init-output.log" - | grep -iE 'github|wget|releases/latest' || echo "clean"`
Expected: `clean`.

- [ ] **Step 6: The A9 regression check**

Launch two runs of the same type as close together as you can manage, then:
```bash
aws s3 ls "s3://baas-<prefix>/runs/<project>/" | tail -2
baas results --limit 4
```
Expected: two distinct prefixes and two distinct rows. Under the old scheme, two runs of one type in the same second collided.

- [ ] **Step 7: A run that dies is still identifiable**

Kill a run mid-flight (`aws ec2 terminate-instances --instance-ids <id>` while it is benchmarking), then read its `environment.json`.
Expected: the prefix exists, the manifest names the project, branch, run id and instant, and no measurement was stored — which is exactly the case the opaque id had to be rescued from.

- [ ] **Step 8: Both download argument shapes**

Run: `baas download <runId> -o /tmp/by-id` and `baas download <an old result path> -o /tmp/by-path`
Expected: both succeed and both directories are populated.

- [ ] **Step 9: The reactor build refuses to guess**

Run, from this repository with an unreleased build: `baas run jmh -- <FakeBenchmark> -f 1 -wi 1 -i 1`
Expected: exit 1, a message naming `--runner-jar`, before any Maven build and before any upload. Then re-run with `--runner-jar benchmark-runner/target/benchmark-runner.jar`.
Expected: the run proceeds, and `aws s3 ls "s3://baas-<prefix>/releases/"` shows nothing new — an unreleased artifact must never reach the release prefix.

- [ ] **Step 10: The release slot is seeded once**

After the first run from a released CLI:
```bash
aws s3 ls "s3://baas-<prefix>/releases/<version>/"
```
Expected: one `benchmark-runner.jar`. Note its `LastModified`, run again, and confirm it is unchanged — upload-if-absent must not overwrite.

- [ ] **Step 11: Comparability against history**

Run the same benchmark CI has history for, then compare:
```bash
baas results --benchmark-name '<that benchmark>' --limit 20
```
Expected: the new score sits inside the historical spread. **State the spread you observed.** Run-to-run variance here is large — CI history spans roughly 10.0M-29.6M ops/s on one benchmark — so a difference inside that band is not evidence of a regression, and a difference outside it must be investigated rather than accepted. Re-keying moves where bytes land and changes nothing about what is measured, so a genuine shift means something else moved: check `baas env diff` between the two runs before concluding anything.

- [ ] **Step 12: Record the results**

Write the observed numbers, the spread and the `env diff` output into `openspec/changes/unified-run-prefix/verify.md` when the verify artifact is produced. Do not mark this plan complete on a green build alone — nothing in the automated suite executes `RunCommand.call()`.
