# DynamoDB Results Store — Implementation Plan (Phase 1: §1–§2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve the change's blocking assumptions, then make `baas run` forward caller-supplied and instance-observed tags to the runner so they reach the stored result.

**Architecture:** `UserDataScriptBuilder` gains a tag map that it renders into the runner invocation, mirroring the existing `BENCHMARK_PARAMETERS` export-plus-`eval` pattern. CLI-derived tags (`project`, `commit`, user `--tag` values) are injected as literals from Java; instance-observed tags (`jdk`, `cpuModel`, `cpuArch`) are captured as shell variables on the box and referenced, so a result's tags can never disagree with its own `environment.json`.

**Tech Stack:** Java 25, Maven multi-module, picocli, JUnit 6, AssertJ, bash user-data rendered to Base64.

**Scope note:** This plan covers `tasks.md` §1 and §2 only. §3–§12 are planned after Task 1 resolves the row count, because task 1.2 can change the partition key shape that §3 and §6 encode.

## Global Constraints

- **No `set -e` in the user-data script.** If the IMDSv2 fetch fails under `set -e` the script exits before the watchdog starts and orphans a paid instance.
- **The watchdog starts immediately after `INSTANCE_ID` resolves.** Never move code above it.
- **User-data installs nothing.** No `yum`, no downloads. The toolchain is baked into the AMI.
- **Every manifest value is captured into a shell variable first.** The heredoc body contains only `${VAR}` references. Values that can contain `"` or `\` go through `json_escape`.
- **Tag values must be the values observed on the instance**, not the ones the CLI passed down.
- **`MANIFEST_SCHEMA_VERSION` must be bumped when a manifest field is added or renamed.**
- **`pom.xml` version stays `0.0.0-semantically-released`.** Never bump it by hand.
- **Diagnostics go to the logger (stderr); command payloads stay on `System.out`.**
- Run the full reactor before trusting a green build: `mvn -pl benchmark-runner verify` alone fails.

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `baas-cli/src/main/java/pl/wsztajerowski/baas/infra/UserDataScriptBuilder.java` | Renders the user-data script | Add a runner-tag parameter; render CLI tags; capture and forward observed JDK/CPU tags; add `cpuArch` to the manifest |
| `baas-cli/src/main/java/pl/wsztajerowski/baas/commands/RunCommand.java` | Orchestrates a run | Add `--project`; derive project and commit from git; pass the tag map to the builder |
| `baas-cli/src/test/java/pl/wsztajerowski/baas/infra/UserDataScriptBuilderTest.java` | Guards the script's invariants | New tests for tag forwarding, ordering, and manifest/tag agreement |
| `openspec/changes/dynamodb-results-store/design.md` | Records decisions | Only if Task 1 changes the partition key |

---

## Task 1: Resolve blocking assumptions

No code. Every later task's shape depends on these answers, so record them in writing before touching anything.

**Files:**
- Modify (conditionally): `openspec/changes/dynamodb-results-store/design.md`
- Modify: `openspec/changes/dynamodb-results-store/tasks.md` (tick §1 boxes, append findings)

**Interfaces:**
- Produces: the confirmed partition-key shape (`RESULT#<project>` or `RESULT#<project>#<yyyy>`) that Tasks in §3/§6 will encode; the confirmed known-tag-key list.

- [ ] **Step 1: Count the Atlas documents**

Run against the Atlas cluster (the connection string is in SSM at `/<prefix>/mongo/connection-string`):

```javascript
mongosh "$BENCHMARK_DB_CONNECTION_STRING" --eval "
  print('jmh_benchmarks: ' + db.getCollection('jmh_benchmarks').countDocuments({}));
  print('jcstress_tests: ' + db.getCollection('jcstress_tests').countDocuments({}));
"
```

Record both numbers in `tasks.md` under §1.1.

- [ ] **Step 2: Decide the partition key from the count**

Thresholds from `design.md` ("Risks / Trade-offs", first bullet), at ~1 KB per item:

| Total documents | Decision |
|---|---|
| < 100k | Keep `pk = RESULT#<project>`. Tick 1.1 and 1.2, no design change. |
| ≥ 100k | Adopt `pk = RESULT#<project>#<yyyy>`. Edit `design.md` (the key block under "The table is one item per measurement…" and the first Risks bullet) and `specs/results-store-schema/spec.md` ("Item key encoding"). |

- [ ] **Step 3: Inventory the tag keys actually present**

```javascript
mongosh "$BENCHMARK_DB_CONNECTION_STRING" --eval "
  const keys = new Set();
  db.getCollection('jmh_benchmarks').find({}, {'benchmarkMetadata.tags': 1}).forEach(d => {
    const t = d.benchmarkMetadata && d.benchmarkMetadata.tags;
    if (t) Object.keys(t).forEach(k => keys.add(k));
  });
  print([...keys].sort().join('\n'));
"
```

Compare against the vocabulary in `proposal.md` (`project`, `type`, `commit`, `jdk`, `cpuModel`, `cpuArch`, `instanceType`, `imageVersion`). Record any key the migration must map or drop under §1.3.

- [ ] **Step 4: Confirm `environment.json` carries what the tags need**

Read `UserDataScriptBuilder.SCRIPT_BODY` and confirm which of `jdk`, `cpuModel`, `cpuArch` are already captured. Expected finding, to be confirmed: `CPU_MODEL` and `JVM_VERSION` exist; **`cpuArch` does not** and must be added in Task 4. Record under §1.4.

- [ ] **Step 5: Confirm no CLI code path reads MongoDB**

```bash
grep -rn "mongo\|Mongo" --include="*.java" baas-cli/src/main
```

Expected: hits only in `ResultsQueryService`, `ResultsCommand`, `SetupCommand`, `ConfigSetSubcommand`, `ConfigSyncSubcommand` — all of which this change removes. Any *other* hit invalidates the "CLI never learns Mongo exists" decision and must be raised before proceeding. Record under §1.5.

- [ ] **Step 6: Decide the migrated-row project default**

Historical rows carry `project=lynx-journal`. Pick the value rows lacking the tag receive and write it into §1.6 — this becomes the constant task 9.4 uses.

- [ ] **Step 7: Commit the findings**

```bash
git add openspec/changes/dynamodb-results-store/
git commit -m "docs(openspec): record dynamodb-results-store blocking assumption findings"
```

---

## Task 2: Forward caller-supplied tags to the runner

Today `baas run --tag foo=bar` sets an EC2 *instance* tag and nothing else, so it never reaches `benchmarkMetadata.tags`. This is the bug every query requirement rests on.

**Files:**
- Modify: `baas-cli/src/main/java/pl/wsztajerowski/baas/infra/UserDataScriptBuilder.java:157-186`
- Modify: `baas-cli/src/main/java/pl/wsztajerowski/baas/commands/RunCommand.java:212-216`
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/infra/UserDataScriptBuilderTest.java`

**Interfaces:**
- Produces: `UserDataScriptBuilder.build(String region, String bucket, String ssmPrefix, String benchmarkType, String requestId, String resultPath, int benchmarkTimeoutSeconds, int wallClockHardKillSeconds, String imageVersion, String amiId, String runnerJarS3Key, List<String> benchmarkParams, Map<String,String> runnerTags)` — `runnerTags` is rendered as literal `--tag "k=v"` arguments. Task 3 supplies `project` and `commit` through it.

- [ ] **Step 1: Add the unused parameter so the test can compile and fail on its assertion**

In `UserDataScriptBuilder.java`, add `import java.util.Map;` and change the signature (line 157-160) to end with the new parameter. Do **not** use it yet:

```java
    public String build(String region, String bucket, String ssmPrefix, String benchmarkType,
                        String requestId, String resultPath, int benchmarkTimeoutSeconds,
                        int wallClockHardKillSeconds, String imageVersion, String amiId,
                        String runnerJarS3Key, List<String> benchmarkParams,
                        Map<String, String> runnerTags) {
```

In `RunCommand.java` at the call site ending line 216, pass an empty map for now:

```java
            runnerImage.imageVersion(), runnerImage.amiId(), runnerJarS3Key,
            benchmarkParams, Map.of());
```

In `UserDataScriptBuilderTest.java`, update the `script()` helper (lines 16-22) and add a tag-aware variant:

```java
    private String script() {
        return script(Map.of());
    }

    private String script(Map<String, String> runnerTags) {
        String encoded = new UserDataScriptBuilder().build(
            "eu-central-1", "baas-a1b2c3d4", "a1b2c3d4", "jmh",
            "jmh-20260724_120000", "main/jmh/20260724_120000", 7200, 7500,
            "1.0.0", "ami-0123456789abcdef0", null, List.of("MyBenchmark", "-f", "1"),
            runnerTags);
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }
```

- [ ] **Step 2: Write the failing test**

Add to `UserDataScriptBuilderTest.java`:

```java
    /**
     * `baas run --tag` used to reach the EC2 instance only, so no result from the CLI path
     * carried a caller tag. The whole tag-based query model depends on this reaching the runner.
     */
    @Test
    void forwardsCallerSuppliedTagsToTheRunner() {
        String script = script(Map.of("project", "lynx-journal", "experiment", "gc tuning"));

        assertThat(script)
            .contains("--tag \"project=lynx-journal\"")
            .contains("--tag \"experiment=gc tuning\"");

        assertThat(script.indexOf("RUNNER_TAGS_ARRAY[@]"))
            .as("caller tags have to be arguments of the runner invocation itself")
            .isGreaterThan(script.indexOf("java -jar /app/benchmark-runner.jar"))
            .isLessThan(script.indexOf("EXIT_CODE=$?"));
    }

    @Test
    void rendersNoTagArgumentsWhenNoneAreSupplied() {
        String script = script(Map.of());

        assertThat(script)
            .as("an empty array must still expand cleanly under the eval pattern")
            .contains("export RUNNER_TAGS=''");
    }

    @Test
    void escapesSingleQuotesInTagValues() {
        String script = script(Map.of("note", "it's fine"));

        assertThat(script)
            .as("the export is single-quoted, so an embedded quote must be escaped")
            .contains("--tag \"note=it'\\''s fine\"");
    }
```

- [ ] **Step 3: Run the test and confirm it fails**

Run: `mvn -pl baas-cli test -Dtest=UserDataScriptBuilderTest`
Expected: FAIL — `forwardsCallerSuppliedTagsToTheRunner` reports the script does not contain `--tag "project=lynx-journal"`.

- [ ] **Step 4: Render the tags in the script body**

In `SCRIPT_BODY`, immediately after the existing `eval "BENCHMARK_PARAMS_ARRAY=(${BENCHMARK_PARAMETERS})"` line (line 128), add:

```
        # Caller-supplied tags (project, commit, and any --tag the operator passed). Same
        # export-then-eval pattern as BENCHMARK_PARAMETERS, so values containing spaces stay
        # single argv tokens. Instance-observed tags are appended separately below.
        eval "RUNNER_TAGS_ARRAY=(${RUNNER_TAGS})"
```

Then extend the runner invocation (lines 133-140) so the array expands before the benchmark params:

```
        timeout "${BENCHMARK_TIMEOUT}" java -jar /app/benchmark-runner.jar "${BENCHMARK_TYPE}" \\
          --request-id     "${REQUEST_ID}" \\
          --result-path    "${RESULT_PATH}" \\
          --s3-bucket      "${S3_BUCKET}" \\
          --benchmark-path /app/benchmark-under-test.jar \\
          --tag "imageVersion=${IMAGE_VERSION_ACTUAL}" \\
          --tag "instanceType=${INSTANCE_TYPE}" \\
          "${RUNNER_TAGS_ARRAY[@]}" \\
          "${BENCHMARK_PARAMS_ARRAY[@]}"
```

- [ ] **Step 5: Export the rendered tags from `build()`**

In `build()`, before the `String script = ...` assignment, add:

```java
        String tagArgs = runnerTags.entrySet().stream()
            .map(e -> "--tag \"" + e.getKey() + "=" + e.getValue() + "\"")
            .collect(java.util.stream.Collectors.joining(" "));
```

and add this line to the export block, next to `BENCHMARK_PARAMETERS`:

```java
            "export RUNNER_TAGS='" + tagArgs.replace("'", "'\\''") + "'\n" +
```

- [ ] **Step 6: Run the tests and confirm they pass**

Run: `mvn -pl baas-cli test -Dtest=UserDataScriptBuilderTest`
Expected: PASS, including the pre-existing `passesEnvironmentTagsToTheRunnerNotJustToTheInstance` and `environmentTagsReuseTheObservedValues`.

- [ ] **Step 7: Commit**

```bash
git add baas-cli/src/main/java/pl/wsztajerowski/baas/infra/UserDataScriptBuilder.java \
        baas-cli/src/main/java/pl/wsztajerowski/baas/commands/RunCommand.java \
        baas-cli/src/test/java/pl/wsztajerowski/baas/infra/UserDataScriptBuilderTest.java
git commit -m "fix(cli): forward caller-supplied tags to the runner, not just the instance"
```

---

## Task 3: Derive and forward `project` and `commit`

**Files:**
- Modify: `baas-cli/src/main/java/pl/wsztajerowski/baas/commands/RunCommand.java` (option block around line 89-97; helper near `currentGitBranch()` at line 356-367; call site at line 212-216)
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/commands/RunCommandTest.java` (create if absent)

**Interfaces:**
- Consumes: `UserDataScriptBuilder.build(..., Map<String,String> runnerTags)` from Task 2.
- Produces: `RunCommand.resolveProject()` returning `String`, and `RunCommand.currentGitCommit()` returning `String`. Both throw `IllegalStateException` when git is unavailable.

**Design note — `branch` is deliberately not auto-tagged.** `RunCommand` already has `--branch` (line 92) which feeds the S3 result path. Per `design.md` ("`branch` is a custom user tag, not a known key"), it is **not** copied into the tag map; an operator who wants branch grouping passes `--tag branch=<name>`. Do not "helpfully" wire it up.

- [ ] **Step 1: Write the failing tests**

Create `baas-cli/src/test/java/pl/wsztajerowski/baas/commands/RunCommandTest.java`:

```java
package pl.wsztajerowski.baas.commands;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `mvn -pl baas-cli test -Dtest=RunCommandTest`
Expected: FAIL to compile — `projectFromToplevel` does not exist.

- [ ] **Step 3: Add the option and the helpers**

In the option block after line 93, add:

```java
    @Option(names = "--project", description = "Project name for the results partition (defaults to the git repository name).")
    String project;
```

Near `currentGitBranch()` (line 356), add:

```java
    /** Split out from the git call so the parsing is unit-testable without a repository. */
    static String projectFromToplevel(String toplevel) {
        if (toplevel == null || toplevel.isBlank()) return null;
        String trimmed = toplevel.strip();
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        int slash = trimmed.lastIndexOf('/');
        String name = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        return name.isEmpty() ? null : name;
    }

    private String gitOutput(String... args) {
        try {
            var pb = new ProcessBuilder(args).redirectErrorStream(true);
            var proc = pb.start();
            String out = new String(proc.getInputStream().readAllBytes()).trim();
            return proc.waitFor() == 0 ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveProject() {
        if (project != null && !project.isBlank()) return project;
        String derived = projectFromToplevel(gitOutput("git", "rev-parse", "--show-toplevel"));
        if (derived == null) {
            throw new IllegalStateException(
                "Cannot determine the project name: not inside a git repository. Pass --project <name>.");
        }
        return derived;
    }

    private String currentGitCommit() {
        String commit = gitOutput("git", "rev-parse", "HEAD");
        return commit != null ? commit : "unknown";
    }
```

- [ ] **Step 4: Run and confirm the unit tests pass**

Run: `mvn -pl baas-cli test -Dtest=RunCommandTest`
Expected: PASS.

- [ ] **Step 5: Extract the tag-map construction so it is testable**

The map must be built outside `call()`, which cannot run without AWS. Add next to the helpers:

```java
    /**
     * Extracted from call() so it can be tested without AWS. Caller tags come first so a
     * deliberate --tag project=... still wins over the derived value.
     */
    Map<String, String> buildRunnerTags(String benchmarkType, String project, String commit) {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("project", project);
        tags.put("commit", commit);
        tags.put("type", benchmarkType);
        tags.putAll(extraTags);
        return tags;
    }
```

- [ ] **Step 6: Test the tag map**

Add to `RunCommandTest.java`:

```java
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
```

Run: `mvn -pl baas-cli test -Dtest=RunCommandTest`
Expected: FAIL first (method missing), then PASS after Step 5 is in place.

- [ ] **Step 7: Wire it into the call site**

Resolve the project **before** any AWS call, so an unresolvable project fails before provisioning. Immediately before the `String userData = ...` assignment (line 212), add:

```java
        Map<String, String> runnerTags = buildRunnerTags(benchmarkType, resolveProject(), currentGitCommit());
```

and change the call site's last argument from `Map.of()` to `runnerTags`.

- [ ] **Step 8: Write the failing integration-level assertion**

Add to `UserDataScriptBuilderTest.java`:

```java
    @Test
    void callerTagsPrecedeBenchmarkParameters() {
        String script = script(Map.of("project", "lynx-journal"));

        assertThat(script.indexOf("RUNNER_TAGS_ARRAY[@]"))
            .as("a tag rendered after the params array would be parsed as a JMH argument")
            .isLessThan(script.indexOf("BENCHMARK_PARAMS_ARRAY[@]"));
    }
```

- [ ] **Step 9: Run the full module test suite**

Run: `mvn -pl baas-cli test`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add baas-cli/src/main/java/pl/wsztajerowski/baas/commands/RunCommand.java \
        baas-cli/src/test/java/pl/wsztajerowski/baas/commands/RunCommandTest.java \
        baas-cli/src/test/java/pl/wsztajerowski/baas/infra/UserDataScriptBuilderTest.java
git commit -m "feat(cli): derive project and commit and forward them as runner tags"
```

---

## Task 4: Forward observed JDK and CPU tags, and add `cpuArch` to the manifest

These values must come from the instance, not the CLI, so they are captured as shell variables and referenced — the same rule that already governs `imageVersion` and `instanceType`. `cpuArch` is not currently in the manifest, so adding it as a tag requires adding it there too and bumping the schema version.

**Files:**
- Modify: `baas-cli/src/main/java/pl/wsztajerowski/baas/infra/UserDataScriptBuilder.java` (`MANIFEST_SCHEMA_VERSION` at line 10; capture block lines 55-72; manifest heredoc lines 74-98; runner invocation lines 133-140)
- Test: `baas-cli/src/test/java/pl/wsztajerowski/baas/infra/UserDataScriptBuilderTest.java`

**Interfaces:**
- Produces: shell variables `JDK_VERSION`, `CPU_MODEL_RAW`, `CPU_ARCH` in the script body, and manifest field `cpuArch`. `MANIFEST_SCHEMA_VERSION` becomes `2`.

- [ ] **Step 1: Write the failing tests**

Add to `UserDataScriptBuilderTest.java`:

```java
    @Test
    void forwardsObservedEnvironmentTagsToTheRunner() {
        String script = script();

        assertThat(script)
            .contains("--tag \"jdk=${JDK_VERSION}\"")
            .contains("--tag \"cpuModel=${CPU_MODEL_RAW}\"")
            .contains("--tag \"cpuArch=${CPU_ARCH}\"");
    }

    @Test
    void observedTagsAreCapturedBeforeTheyAreUsed() {
        String script = script();

        assertThat(script.indexOf("--tag \"jdk="))
            .isGreaterThan(script.indexOf("JDK_VERSION=$("));
        assertThat(script.indexOf("--tag \"cpuArch="))
            .isGreaterThan(script.indexOf("CPU_ARCH=$("));
    }

    @Test
    void manifestCarriesCpuArchSoTagsCannotDisagreeWithIt() {
        String script = script();

        assertThat(script)
            .as("a tag with no manifest counterpart breaks the observed-values invariant")
            .contains("\"cpuArch\": \"${CPU_ARCH}\"");
    }

    @Test
    void manifestSchemaVersionIsBumpedForTheNewField() {
        assertThat(UserDataScriptBuilder.MANIFEST_SCHEMA_VERSION).isEqualTo(2);
    }
```

- [ ] **Step 2: Run and confirm failure**

Run: `mvn -pl baas-cli test -Dtest=UserDataScriptBuilderTest`
Expected: FAIL — four new tests fail; `manifestSchemaVersionIsBumpedForTheNewField` reports `expected 2 but was 1`.

- [ ] **Step 3: Bump the schema version**

Change line 10:

```java
    /** Bump when a field is added or renamed, so `baas env diff` can tell structure from content. */
    public static final int MANIFEST_SCHEMA_VERSION = 2;
```

- [ ] **Step 4: Capture the three values**

In the capture block, replace the existing `CPU_MODEL=` line (line 58) with a raw-then-escaped pair, and add the two new captures next to it:

```
        CPU_MODEL_RAW=$(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2- | sed 's/^ *//')
        CPU_MODEL=$(json_escape "$CPU_MODEL_RAW")
        CPU_ARCH=$(uname -m)
        JDK_VERSION=$(java -version 2>&1 | head -1 | sed -n 's/.*"\\(.*\\)".*/\\1/p')
```

- [ ] **Step 5: Add `cpuArch` to the manifest**

In the heredoc, after the `"cpuModel"` line (line 81), add:

```
          "cpuArch": "${CPU_ARCH}",
```

- [ ] **Step 6: Forward the three tags**

Extend the runner invocation so it reads:

```
          --tag "imageVersion=${IMAGE_VERSION_ACTUAL}" \\
          --tag "instanceType=${INSTANCE_TYPE}" \\
          --tag "jdk=${JDK_VERSION}" \\
          --tag "cpuModel=${CPU_MODEL_RAW}" \\
          --tag "cpuArch=${CPU_ARCH}" \\
          "${RUNNER_TAGS_ARRAY[@]}" \\
```

- [ ] **Step 7: Run the tests and confirm they pass**

Run: `mvn -pl baas-cli test -Dtest=UserDataScriptBuilderTest`
Expected: PASS. The existing `producesValidJsonManifest`-style test (which parses the heredoc with Jackson) must still pass — if it fails, the new `cpuArch` line has a trailing-comma or quoting error.

- [ ] **Step 8: Run the full reactor**

Run: `ASYNC_PATH=/app/async-profiler/lib/libasyncProfiler.so mvn clean verify`
Expected: PASS. `ASYNC_PATH` must be exported or `JmhWithAsyncProfilerSubcommandServiceIT` silently skips.

- [ ] **Step 9: Commit**

```bash
git add baas-cli/src/main/java/pl/wsztajerowski/baas/infra/UserDataScriptBuilder.java \
        baas-cli/src/test/java/pl/wsztajerowski/baas/infra/UserDataScriptBuilderTest.java
git commit -m "feat(cli): capture and forward jdk, cpuModel and cpuArch as runner tags"
```

---

## Task 5: Verify against a real run (manual — no automated test covers `baas run`)

`RunCommand.call()` is executed by no test, and `e2e-cloud-test.yml` exercises the GHA path rather than the runner AMI. This step is the only thing that proves the chain end to end.

**Files:** none — this is an AWS operation.

- [ ] **Step 1: Launch a run with a custom tag**

```bash
baas run --tag experiment=gc-tuning jmh -- MyBenchmark -f 1 -wi 1 -i 3
```

Note the printed request ID.

- [ ] **Step 2: Confirm the tags reached the stored result**

Until the DynamoDB store lands (§5 of `tasks.md`), read them back from Atlas:

```javascript
mongosh "$BENCHMARK_DB_CONNECTION_STRING" --eval "
  db.getCollection('jmh_benchmarks')
    .find({'_id.requestId': '<REQUEST_ID>'}, {'benchmarkMetadata.tags': 1})
    .forEach(d => printjson(d.benchmarkMetadata.tags));
"
```

Expected keys: `imageVersion`, `instanceType`, `jdk`, `cpuModel`, `cpuArch`, `project`, `commit`, `type`, `experiment`.

- [ ] **Step 3: Confirm the tags agree with `environment.json`**

```bash
aws s3 cp "s3://<bucket>/<result-path>/environment.json" - | jq '{schemaVersion, cpuModel, cpuArch, jvmVersion, instanceType, imageVersion}'
```

`cpuModel`, `cpuArch` and `instanceType` must match the tag values exactly, and `schemaVersion` must read `2`.

- [ ] **Step 4: Confirm `--project` overrides**

```bash
baas run --project override-test jmh -- MyBenchmark -f 1 -wi 1 -i 1
```

Expected: the stored result carries `project=override-test`.

- [ ] **Step 5: Confirm failure outside a repository**

```bash
cd /tmp && baas run jmh -- MyBenchmark
```

Expected: non-zero exit naming `--project`, and **no EC2 instance launched** (`aws ec2 describe-instances --filters Name=tag:baas-role,Values=runner Name=instance-state-name,Values=pending,running`).

- [ ] **Step 6: Tick §1 and §2 in `tasks.md` and commit**

```bash
git add openspec/changes/dynamodb-results-store/tasks.md
git commit -m "docs(openspec): mark dynamodb-results-store §1-§2 complete"
```

---

## Next

With §1 and §2 done, the Atlas row count is known and the partition key is settled. Re-run `/opsx:continue` — or regenerate this plan scoped to §3–§9 and §11 — to plan the model module, CloudFormation, the store adapters, the query layer and the migration against confirmed values.
