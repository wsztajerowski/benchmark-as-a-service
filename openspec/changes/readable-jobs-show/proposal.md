# Proposal

## Why

`baas jobs show` prints `environment.json` the way it's stored: the raw `java -version` banner,
memory in KiB, `[always] madvise never`, `perf perf version …`, empty fields as `—`. Its tags line
repeats fields already shown and wraps, and its next-step hints carry a logger timestamp and class
name. One stored value is also misleading, not just hard to read: `cpu.cores` is `nproc`, the vCPU
count, so on a c5.2xlarge `cores 8 · threadsPerCore 2` reads as 16 hardware threads when the box
has 4 cores and 8 threads. BaaS is still being tested and holds no production results, so the
manifest's schema can change now without carrying the old one along.

## What Changes

- **`environment.json` schema 7** (breaking for anything reading the file by key; its only readers
  are this CLI's `jobs show` and `jobs diff`, so it ships as a plain `feat`, without a breaking
  marker — design.md, *Resolved Questions*):
  - `cpu.cores` → `cpu.vcpus` (same `nproc` capture); new `cpu.physicalCores` (sockets × cores per
    socket, `""` when `lscpu` lacks either).
  - `memory.totalKb`/`swapTotalKb` → `memory.totalMib`/`swapTotalMib`, captured as whole MiB.
  - new `jvm.release` (`25.0.4`): the value the `jdk` tag already carries, so the tag can be
    checked against the job's own manifest.
  - Every other field is unchanged and stays raw.
- **`baas jobs diff`** compares every field exactly as stored. The KiB→MiB comparison of the
  memory fields is removed, because v7 records MiB. There is no alias from v6 names to v7 names:
  a v6 manifest still parses and compares, and its renamed fields show as present on one side,
  under the existing schema-mismatch warning.
- **`baas jobs show`**, human view only:
  - Environment values go through per-field formatters keyed by group and field (GiB memory,
    `Corretto 25.0.4.7.1 (Amazon.com Inc.)`, `async-profiler 4.0`, `thp always`), with the raw
    value as fallback. Empty values are hidden, and `machine.instanceType` is not repeated.
  - The tags block drops `project` and `type`, shows `hidden=true` as the marker `hidden` next to
    the status, and prints one tag per line.
  - Artifacts are grouped (output, environment, results, reports, input), one per line, with sizes.
  - Runner-written strings are stripped of control characters before they are printed, and
    `--format json` re-serialises the parsed manifest instead of splicing its raw text.
- **The `exclude_from_results` tag is renamed `hidden`**, and `results query --show-excluded` is
  renamed `--show-hidden`. The behaviour is unchanged: hidden measurements stay out of project
  sweeps, the `--all-projects` scan and the project picker, are returned by `--job-id`, and are drawn
  faint under `--show-hidden`. There is no data migration and no alias: a stored row tagged
  `exclude_from_results=true` is no longer hidden.
- **Next-step hints**, for every command that prints them: a plain `Next` block on standard error
  (`  <purpose>  <command>`, aligned), no longer a logger line with timestamp, level and class.
  Interactive-only gating, the two-hint limit and `--deployment` in every command are unchanged.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `runner-image-provisioning`: *Every job records its environment, grouped* (schema 7 fields; the
  `jdk` tag equals `jvm.release`); *Two jobs' environments are compared group by group* (no KiB→MiB
  conversion).
- `job-tracking`: *`baas jobs show` reports one job's execution* (formatted environment, tag
  rules, grouped artifacts with sizes, sanitised text, re-serialised JSON environment).
- `cli-command-structure`: *Next-step hints are shown to people, never to scripts* (the `Next` block
  replaces the logger's `→ <purpose>: <command>` line); *Results filters cover the supported query
  patterns* (`--show-hidden`).
- `benchmark-results-query`: *Excluded results are filtered out* is replaced by *Hidden results are
  filtered out* (the tag and option names; the rule is the same); *A project is chosen when none is
  named* and *Tags can exclude rows* name the new tag.
- `cli-console-output`: *Tables are coloured when interactive, without losing alignment* (rows tagged
  `hidden=true` are de-emphasised under `--show-hidden`).
- `ci-benchmark-execution`: the three self-test requirements tag their jobs `hidden=true` and assert
  with `--show-hidden`.
- `job-tracking`: also *`baas jobs list` shows recent jobs* (a `hidden=true` job is listed like any
  other).

## Impact

- **Code:** `UserDataScriptBuilder` (capture and heredoc, `MANIFEST_SCHEMA_VERSION` 7),
  `EnvironmentManifest` (drops `KIB_FIELDS`/`compared`), `JobsShowSubcommand` (formatters, tags,
  artifacts, JSON), `console.Hints` and its four callers (`jobs show`, `jobs list`, `results query`,
  `run`), the S3 listing that `show` reads (key plus size, same `ListObjectsV2` call), and their tests.
  The tag rename: `TagKeys.HIDDEN` in `baas-model`, `ResultsQueryService`'s filter expression,
  `ResultRow.hidden()`, `ResultsTable`, `ResultsQuerySubcommand`'s option, and the `jobs show` marker.
- **CI:** `e2e-cloud-test.yml` tags both jobs `hidden=true` and asserts with `--show-hidden`.
- **Docs:** `README.md` and the diagrams `baas-ci-e2e`, `baas-results`, `baas-run` and `baas-jobs`
  (tag and option names). `CLAUDE.md` (*Result tagging*'s convention row and the e2e paragraph name the
  tag; *S3 result layout* says schema 6; *Diagnostics go to the logger* gains the
  hint block as its one plain-stderr exception) and the stale user-data comment on the manifest's
  identity. `docs/diagrams/baas-jobs-show-diff.mmd` names no manifest field and stays as it is.
- **Findings:** closes G19's user-data-comment bullet (the rest of G19 stays open). Closes X7 for
  `jobs show` (text sanitising and the JSON splice); X7 stays open for `ResultsTable` and the
  general `Console` text path.
- **Cost:** none. No AWS resource changes, and no new API calls (artifact sizes come from the
  listing `show` already makes).
- **Not changed:**
  - The manifest is still written and uploaded before the benchmark, every value is captured into
    a shell variable first, and user-data still installs nothing.
  - Machine-observed tags still come from the same shell variables.
  - `jobs show` still shows no measurements; a summary was declined for now.
  - The `--format json` shapes of `show` keep their members.
  - Stored measurements and job items tagged `exclude_from_results=true` are not migrated: after the
    rename they are visible. BaaS holds no production data yet.
  - `--exclude-tag` is a different filter and keeps its name.
  - Stored v6 manifests are not migrated or rewritten. Removing the test deployments' v6 jobs is a
    separate, manual step.
  - Final JVM flags of the benchmark forks are out of scope (parked).
