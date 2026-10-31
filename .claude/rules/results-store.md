---
paths:
  - "baas-model/**"
  - "baas-cli/src/*/java/pl/wsztajerowski/baas/results/**"
  - "benchmark-runner/src/*/java/pl/wsztajerowski/services/**"
---

# Where a job's data lives: S3 layout, results table, tags

Keys, prefixes and tags are the contract every reader depends on; a mistake returns zero rows, not an error.

- **One job is one prefix, one id and one instant.** Everything a job produces or consumes lives
  under `jobs/<project>/<jobId>/`, built only by `JobLayout`/`JobId` in `baas-model` — the same
  reason `ResultKeys` owns DynamoDB keys. A hand-built prefix does not fail to compile; it points at
  nothing, and that presents as an empty download rather than as an error. Splitting inputs and
  results back into two trees is the split this design removed.

## S3 result layout

One job is one prefix: `<result-path>` = `jobs/<project>/<jobId>/`, where `jobId` is
`<UTC instant, ISO basic, milliseconds>Z-<8 hex>` (e.g. `20260820T174432812Z-a3f9c21b`) — fixed 28
characters, time-ordered so a listing reads chronologically, entropy-suffixed so two jobs starting
in the same millisecond cannot collide. Nothing parses it; the format is a readability convention,
not a contract. Built only by `JobLayout`/`JobId` in `baas-model`, never by hand.

Stdout lands in `jmh-output.txt` or `jcstress-output.txt` (jobs of the retired profiled types hold
`jmh-with-async-output.txt` or `jmh-profiler-output.txt`, which `jobs show` still groups as output);
profiling artifacts — every `async` and `jfr` profiler is given `dir=/app/profiler-output` — go under the directory JMH names each benchmark's
profile, `<fully.qualified.BenchmarkName>-<Mode>` plus `-<param>-<value>` per `@Param`
(`BenchmarkParams.id()`). **The runner never predicts that name:** it uploads the profiler's whole
output tree as written, because the JMH that chooses it is the one inside the benchmark JAR, and
predicting `<benchmark>-<Mode>` silently uploaded nothing for every `@Param` benchmark (#115). For
the same reason no measurement carries a profiler path — a job's artifacts are reached by its id. The
non-obvious entries:

| Key | Meaning |
|---|---|
| `launch-error.txt` | Only for a job whose `RunInstances` failed: the AWS error code, message and job id, and what was requested. There is no instance and so no boot log; this is what `baas jobs download <jobId>` then has to show |
| `cloud-init-output.log` | Runner boot log, uploaded before every termination, a cancelled or timed-out job's included (with the process output and `logs/` so far) — start here when a job fails before producing output |
| `environment.json` | The environment the job measured on, and nothing else: `schemaVersion` (8) and eight groups — `machine` (image version, AMI, instance type), `cpu`, `memory`, `os`, `jdk` (the JDK the benchmark ran on: `id`, `home`, banner `version`, `release`, `vendor`, `vendorVersion`, `vmName`), `runnerJdk` (`java` on `PATH`, which ran the runner: `release`, `vendorVersion`), `tools`, `tunables`. No job identity: that is on the job item and its tags. Written **before** the benchmark, so it survives a failed job. Values stay raw (version banners, sysfs syntax); `baas jobs show` shortens them for reading and `baas jobs diff` compares them as stored, group by group, also comparing `packages.txt` when the AMIs differ. Schema 7 renamed `cpu.cores` to `vcpus` (it was always `nproc`) beside a new `physicalCores`, records memory as `totalMib`/`swapTotalMib`, and adds `jvm.release`. Schema 8 renamed `jvm` to `jdk` (`name` to `vmName`), reads it from the selected JDK's own `java` rather than `PATH`'s, and added `jdk.id`, `jdk.home` and `runnerJdk`; on an image from before JDK selection `jdk.id` and `jdk.home` are empty. A renamed field never keeps its old name with a new meaning, and nothing maps an old schema's name onto a new one: across the change `diff` shows both, under its schema warning. Both deployments' data was erased when schema 8 shipped (9.0.0), so no stored manifest carries `jvm`. |
| `jmh-result.json` | JMH's own machine-readable output, verbatim. The stored item drops `rawData` and `scorePercentiles` for the 400 KB cap, so this is the only place they survive, fetched with `baas jobs download <jobId>`; `resultJsonKey` on the item points here | A profiler listing (`-- -lprof` alone) has none and stores no measurement: its answer is `jmh-output.txt`, and its job item ends `completed` like any other
| `packages.txt` | `rpm -qa`, split out because several hundred lines would drown the manifest's ~20 fields |
| `logs/**/*.log` | Any `.log` up to 8 levels *below the working directory* (hence the `/app` invariant in `user-data.md`), keyed by its relative path so same-named files cannot collide. Every benchmark type ships them; async-profiler's own logs land under `logs/profiler-output/` |
| `input/` | The job's own inputs — `benchmark.jar`, and `runner.jar` only when `--runner-jar` overrode the pinned one. Inside the job prefix, so a consumer has one sub-prefix to skip rather than filenames to special-case |
| `releases/<version>/benchmark-runner.jar` | The version-pinned runner, outside the job tree (seeding rules in `user-data.md`). `releases/`, not `runner/`, because a prefix one character from `jobs/` would need disambiguating in every listing |
| `image-builds/` | Image Builder build logs (written by the build instance, not by a job) |

`environment.json` is the **observation**; `infra/runner-image.yaml` is the **declaration**. The
observation is strictly richer — it carries what the image cannot control (instance type, CPU
model, resolved patch levels) and is the one that answers whether two results are comparable.
Never infer the environment of a past run from the declaration in the working tree.

## Results table

`<prefix>-results`, DynamoDB, on-demand, `DeletionPolicy: Delete` — like the bucket, it leaves
with its deployment: **nothing survives a teardown** except through `baas admin deployment export`
(`deployment-config.md`).

Two kinds of item: one per measurement — per JMH benchmark method, per JCStress *job* (JCStress
names only non-passing tests, so per-test items would cover failures alone) — and one per job (*Job
items*, below). No derived index items.

| | |
|---|---|
| `pk` | `RESULT#<project>` — `--project`, or the benchmark JAR's git repository when `git.resolveProject` is on |
| `sk` | `<class>#<method>#<mode>#<timestamp>#<jobId>[#<params>]`, or `JCSTRESS#<timestamp>#<jobId>` |
| GSI `jobId-index` | `gsi1pk` = job ID; the one access path that is not a project sweep |

`mode` is in the sort key because a `-bm thrpt,avgt` run produces two results whose class and method
are identical; without it they differ only by a millisecond and one silently overwrites the other.
Timestamps are fixed-width UTC with exactly three fractional digits — `Instant.toString()` omits
trailing zeros, which makes keys of differing length that misorder as strings, and that surfaces as
missing rows rather than as an error.

`#<params>` is there for the same reason as `mode`, one level down: a `@Param` sweep's variants share
class, method, mode and the job's single timestamp. Without it, `BatchWriteItem` rejected the whole batch
(`Provided list of item keys contains duplicates`), failing the job and taking any other benchmark in the
batch with it; across batches, the last variant silently overwrote the rest. It is `name=value` sorted by
name, joined by `,` (`ResultKeys.formatParams`), and **appended only when present** — not a fixed field
like `mode` — so every key written before it, and every benchmark without params, is byte-identical.
Nothing parses a key, so an optional trailing field costs nothing. The GSI sort key carries no params:
that index is queried by job id alone and need not be unique. Params are a benchmark's identity,
not a tag: they are a separate `params` map on the item, not part of the query surface.

**Keys are constructed only in `ResultKeys`, items only in `MeasurementItemMapper`**, both in
`baas-model`. Hand-encoding either elsewhere is how a query returns zero rows instead of failing to
compile. Non-finite scores are stored as absent: DynamoDB's `N` rejects `NaN`, and JMH reports one
for any single-iteration run.

## Result tagging

**Tags are the entire query surface.** There is no field-per-dimension: `baas results query` filters,
groups and excludes on tags alone, so anything you want to slice by has to be one.

The vocabulary is defined once, in `baas-model`'s `TagKeys`:

| Group | Keys | Set by |
|---|---|---|
| Machine-observed | `imageVersion`, `instanceType`, `jdkId`, `jdkVersion`, `jdkVendor`, `cpuModel`, `cpuArch` | The instance, from the same shell variables `environment.json` uses. A caller `--tag` for one of these is **rejected**, not overridden. The three JDK tags describe the JDK the benchmark ran on (`jdk.id`, `jdk.release`, `jdk.vendor`): `jdkId` is the `--jdk` id, the image's default (`corretto-25`) when none was given, and absent on a job from an image that predates JDK selection — no id was observed, so none is claimed |
| Derived | `type`, `project`, `source`, `cliParameters`, `profilers` | `baas run`. `type` is `jmh` or `jcstress` — profiling is no type; rows stored before `benchmark-args-pass-through` may say `jmh-with-async`/`jmh-with-prof` and carry no `profilers`. `cliParameters` is everything after `--`, verbatim — the only record of forks, iterations and the profiler options a job ran with, since `jmh-result.json` keeps no profiler options and the item keeps none of either. Absent when nothing follows `--`. `profilers` is the `-prof` names alone, sorted, de-duplicated and comma-joined (`async,gc`), absent when nothing is profiled; the results table marks such a score `*` and `results diff` warns when two jobs' differ. `type`, `cliParameters` and `profilers` are reserved like the observed keys, and a `--tag project=` is rejected: `--project` (or git) is its only input, since the runner partitions by the tag while the S3 prefix and job item take `--project`, and two inputs split one job across two projects. `source` alone is caller-overridable by design. `source` is `ci` when the environment says so (`CI` or `GITHUB_ACTIONS` set and not `false`) and `local` otherwise — a `--tag source=nightly` is accepted, not rejected, because how a job was triggered is not something the instance observes |
| Caller-supplied | `commit`, `branch` | `--tag` only — never derived, absent when not passed |
| Convention | `options`, `hidden` | Free-form. `hidden=true` is filtered out server-side — except under `--show-hidden` (shown faint) and `--job-id`; the picker also omits a project holding only hidden rows; `jobs list` hides nothing and `jobs show` marks it beside the status. It is a convention, not a field. Named `exclude_from_results` until `readable-jobs-show`, renamed without a migration: a stored row carrying the old key is not hidden |

`imageVersion` is the image's *label*: the base version (`1.3.0`), or `1.3.0+ext.<sha256[0:8]>` when
the deployment has an extension. A filter on `1.3.0` therefore never returns an extended image's
results, and one extension is labelled alike in every deployment. `jdkVendor` exists because two
vendors' builds of one release share a `jdkVersion`; `jdkId` because one deployment's ids are its
own, chosen by its extension.

**JDK names what is installed, chosen and ran; JVM is left to runtime behaviour.** `--jdk`, the
`jdk*` tags, the `jdk` and `runnerJdk` manifest groups and `/opt/jdks` all say JDK; "JVM" stays for
what a running VM does (flags, the parked `jvm-flags.json`) and for JMH's own `-jvm`/`-jvmArgs`. The
tags were `jdk` and `jvmVendor`, and the group `jvm`, until `multiple-jdks` (9.0.0), renamed without a
migration because both deployments' data was erased at that rollout.

`branch` used to survive only as a segment of the result path and was stored nowhere. The unified
prefix drops that segment, so what the path stopped carrying the tags now carry — which is the
whole point of tags being the query surface.

Unknown keys pass through — `baas results query` warns only when a `--tag` names a key no row carries.
`baas results query` lists every measurement by default, newest first, 20 at a time in a table and
all of them in `json`/`csv` — a pipe cannot see the stderr note about a cut (same for `jobs list`). `--best-per <tag>`
keeps the best score per `(project, benchmark, params, mode, <tag>)` — the highest for throughput, the
lowest for a time-per-operation mode — and rows carrying no such tag are bucketed rather than dropped.
Project is in the key because `--all-projects` can put two projects' identically named benchmarks side
by side; params because a sweep's variants are different workloads; mode because one benchmark run as
`thrpt` and `avgt` is two numbers in two units — before `jobs-command`, "best" ignored the mode and
always kept the highest score, the slowest result for `avgt`.
The table never shows params in its columns — `-v` prints a `params` line above the `tags` line,
and between them any JMH secondary metrics (`metrics`) and a JCStress job's failing tests (`failed`).
A JCStress row's SCORE is `passed/total`, marked `✗` when a test failed or errored, and JSON carries
`jcstress {total, passed, failed, errors, failedTests}`: the job itself ends `completed` and `baas run`
exits 0 whatever its tests found — the status says JCStress ran, not what it measured (decided 2026-10-20).
`--all-projects` and the project picker are the only `Scan`s; a named project is one `Query`.

- **`commit` and `branch` are absent unless the caller tags them, never `"unknown"`.** A placeholder
  value is indistinguishable from a real one at query time — the same non-answer wearing a value's
  clothing that produced `RESULT#unknown` (`CLAUDE.md`, *What isn't there*). Tags are the entire query surface, so a fake value
  there is worse than a missing one. They are never derived: only `--tag branch=… --tag commit=…`
  supplies them.
- **Git is consulted only when `git.resolveProject` is on, and only for `project`.** Off by default
  (`baas config set --git-resolve-project true`). `baas run` then derives the project from the
  repository **holding `--benchmark-jar`** — never the working directory, which is incidental to
  what is measured — and `baas results query` from the working directory's repository, skipping its
  project picker. A derived default that applied without being asked is what made the same command
  record or read a different partition depending on where it was typed.

**`baas results diff <jobA> <jobB>` never compares across libraries, and decides that on the stored
`kind`, never the `type` tag.** `kind` is required on every item; `type` is a tag nothing enforces.
JMH jobs share a kind and compare, rows of the retired profiled types included; JCStress against JMH is refused, and JCStress against
JCStress too until verdict diffs exist (`results-diff`). Pairs are `--best-per`'s identity less the
project, and its direction (`ResultsGrouping.lowerIsBetter`) is reused, never copied. A verdict is
called only when the `score ± error` intervals do not overlap; a pair in different units is shown,
never converted. Exit 0 whatever the verdicts, like a JCStress job's `completed`: gate CI with `jq`.
A `cpuModel` difference is warned about on its own line, before the one naming the other observed
tags: comparing JDKs is one job per JDK, whose `jdk*` tags differ on purpose, and two jobs of one
instance type can land on different CPUs (Xeon 8275CL and 8124M on `c5.2xlarge`, ~9% apart).
Existence comes from the job item in the same index `Query`, so it tells an unknown id from a job that
measured nothing.

### Job items

Every job since `run-status-in-dynamodb` also has one **job item**: `pk = JOB` (one partition for
every project — the questions it answers, *what is in flight* and *what happened to my job*, are
deployment-wide), `sk = <createdAt>#<jobId>`, `gsi1pk = <jobId>`, `gsi1sk = JOB`. Keys come from
`ResultKeys`, the item from `JobItemMapper`, the status vocabulary and terminal set from `JobStatus`.

- **Only the CLI's `launching` reservation creates it**, with every identity field and the
  CLI-side tags. It is written before `RunInstances`, and a reservation that cannot be written
  launches nothing. Every later write — `launched`, `running`, `completed`/`failed:<n>`,
  `timed-out`, `cancelled`, `launch-failed` — is a conditional `UpdateItem` requiring
  `attribute_exists(pk)` and refusing to replace a terminal status: the first outcome wins.
- **The instance writes only `status` and `instanceId`, and no timestamp**, with the key the CLI
  built (`JOB_SORT_KEY`). The shell never rebuilds the key from `CREATED_AT`, which is
  `Instant.toString()` and varies in width. Its guard expression is the CLI's own
  (`JobItemConditions.NOT_TERMINAL`, in `baas-model`), exported verbatim, and every attribute and
  artifact name in the script is substituted from the model when the class loads.
- **`vanished` is never stored**: a non-terminal status whose instance is not pending or running,
  once the job is older than `JobListing.VANISH_GRACE` (5 min). Before that, a job is still
  launching or not yet visible to `DescribeInstances`, and shows its stored status. `baas jobs
  list` decides from one `DescribeInstances` of the live runners and writes nothing.
- **Every measurement reader excludes job items**: both Scans filter `begins_with(pk, RESULT#)`, the
  index query filters `attribute_exists(kind)` — a filter on `gsi1sk` is refused, it is a key
  attribute — and `MeasurementItemMapper.fromItem` throws on anything else, so a reader that forgets
  fails loudly. A CLI from before job items does none of this: once one exists, its
  `--all-projects`, picker and lookups by id fail. Every CLI must be upgraded (accepted 2026-10-02).
- **IAM**: operator and runner may `UpdateItem` only where `dynamodb:LeadingKeys = JOB`; the runner's
  `PutItem`/`BatchWriteItem` only `RESULT#*`. Teardown reads no item — it names in-flight jobs from
  their instances' tags, since the deployer holds no read of the table.
