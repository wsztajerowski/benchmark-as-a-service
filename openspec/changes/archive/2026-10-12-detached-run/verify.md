# Verification — detached-run

## 1. Blocking assumptions (2026-10-12, `detached-run` at `2a72237`)

| Task | Finding |
|---|---|
| 1.1 | `BaasApp.main` ends in `System.exit(commandLine(app)….execute(args))`, and `System.exit` runs every registered shutdown hook. The hook (`RunCommand` l.462) calls `stop(CANCELLED)` whenever `!session.ended()`, and a confirmed launch does not end the session. So a detached return without the flag cancels and terminates the job it launched: DR1 holds exactly as design.md *Context* states it. |
| 1.2 | `baas jobs show <id> --format json` exits 0 for any job it finds (1 for none, 2 for a malformed id). Its object nests the job under `job` — `{"job":{…,"status","storedStatus",…},"environment":…,"artifacts":…}` — so the resolved status is `.job.status` (`JobListing.resolve`'s, never `vanished` within `VANISH_GRACE` of creation). **Corrected 2026-10-12:** this row first said the object carries `status` at the top level, read from the inner `jobJson` string alone; see W1. The CI loop stops when `status` is terminal by `JobStatus.isTerminal` (`completed`, `timed-out`, `cancelled`, `launch-failed`, `failed:<n>`) or is `vanished`. |
| 1.3 | ADR 0007 is free (0001–0006 exist). The poll is drawn in `baas-run.mmd` and `baas-ci-e2e.mmd`. `baas-states-job.mmd` gains no state or transition, but its header comments name the hook (H) and what `--format json` reports, so they gain one comment line each (no rendered change). The C4 views (`c4-2-container`, `c4-3-component-cli`, `c4-4-deployment`) say "launch / poll" in general terms and stay accurate. |

## 2.6 — full reactor build (2026-10-12)

`mvn verify` with `ASYNC_PATH` exported: **BUILD SUCCESS, 959 tests, 0 failures, 0 errors, 0 skipped**
(runner 73 unit + 44 IT, `baas-model` 18, `baas-cli` 784 unit + 40 IT).
`JmhWithAsyncProfilerSubcommandServiceIT` ran (1 test, not skipped); `RunCommandDetachTest` ran 15.
`java -jar baas-cli/target/baas-cli.jar run --help` lists `--detach` with its help text.

## 3.2 — CI on PR #85 (2026-10-12)

- **Run 38255555596** (`b248336`): `JCStress on EC2 via baas run` passed (attached). `Benchmark on EC2
  via baas run` launched detached correctly — job `20261012T132551853Z-ce92709a` reached `completed`
  and its instance terminated itself — but the follow-up loop never saw it end (W1). Cancelled by
  the user.
- **Run 38257367829** (`283cf92`, the W1 fix): both pass. Benchmark job 1 min 53 s: the detached
  launch printed `"status":"launched"` and exited 10 s into its step (job
  `20261012T134218615Z-4820c08a`); the follow-up loop read `completed` 55 s later, and every later
  assertion — measurements, tags, exclusion, flame graph, JFR, the outcome step — passed. JCStress
  job 1 min 30 s.

## 5.1 — live checks on `baas-381492019823` (2026-10-12, eu-central-1)

This branch's CLI and runner (`b248336`; the CLI is unchanged by `283cf92`), `fake-jmh-benchmarks`
`Incrementing_Synchronized`, `c5.large` (CI was using the 16 vCPU of two `c5.2xlarge`), every job
tagged `exclude_from_results=true` and `source=live-check-detached-run`. Six paid jobs.

| # | Check | Job | Result |
|---|---|---|---|
| L1 | `run --detach --format json` | `…134415663Z-d91fce19` | Exit 0 after 41 s (most of it the 30 MB runner-JAR upload) with `"status":"launched","jobStatus":"launched"`; the instance still `pending` when the CLI had gone. `jobs show` read `launched` → `running` → `completed` (never `vanished`); instance `terminated` by itself |
| L2 | Ctrl+C while `RunInstances` was in flight, detached | `…134804175Z-b478f5fd` | SIGINT 0.0 s after "Launching EC2 instance": exit 130; the hook ran (not yet disarmed), its tag lookup missed ("no instance to terminate"), item `cancelled`; the instance that did launch had its `running` write refused, skipped the benchmark and `terminated`. No summary on stdout, as for an attached run killed by a signal |
| L2′ | First attempt at L2 | `…134416040Z-24762f51` | **Invalid**: launched from a parent shell's `&`, so SIGINT was ignored on entry and `set -m` could not restore it (PR #83's J5 again). The CLI exited 0 and the job completed. Rerun as L2 in the foreground |
| L3 | Detached job, then `jobs terminate --yes` while `running` | `…134416664Z-d0877877` | "Terminated instance …", exit 0; item `cancelled`; instance `shutting-down` |
| L4 | Detached, `--timeout 60`, 400 s benchmark | `…134417248Z-cee495a7` | `failed:124` about 60 s into the benchmark, no CLI running; instance `terminated` |
| L5 | Attached `baas run`, unchanged | `…134418140Z-f9347e21` | Exit 0, results table printed, "Job status: completed"; no detach line |

## 5.4 — `/opsx:verify` (2026-10-12, `283cf92` + this record)

| Dimension | Status |
|---|---|
| Completeness | 20/20 tasks; 3 delta requirements, all implemented |
| Correctness | 3/3 requirements; 14/16 scenarios have a unit test or a live check (W2, W3); 1 covered only by its guard's design (W4) |
| Coherence | Design followed; one recorded deviation (CI names its timeout and margin) |

### Requirement → code → test

| Requirement (delta) | Code | Test / live | Gap |
|---|---|---|---|
| *A detached job is launched, then left to end itself* (job-tracking, ADDED) | `RunCommand` `--detach` (l.158), `afterLaunch` (l.553), `onShutdown` (l.525) | `RunCommandDetachTest` (11 of 15); live L1–L4; CI run 38257367829 | W2, W3 |
| — outlives the CLI | `afterLaunch` returns 0 before `poll` | `aConfirmedDetachedLaunchReturnsZeroWithoutPolling`; L1 | — |
| — CLI exit does not cancel | `onShutdown` checks `detached` | `theHookLeavesADetachedJobAlone`; L1 | — |
| — interrupt during the launch | flag set only after the confirmation | `theHookCancelsADetachedRunInterruptedBeforeItsLaunchWasConfirmed`, `aDetachedLaunchCancelledWhileLaunchingFails`; L2 | — |
| — hung job ended by its watchdog | user-data (unchanged) | none in this change | W2 |
| — operator stops it | `JobTermination` (unchanged) | L3 | — |
| — followed by id, never `vanished` | `JobsShowSubcommand` + `VANISH_GRACE` (unchanged) | `JobListingTest`; L1 | — |
| — failed detached launch | `recordLaunchFailure` returns 1 before `afterLaunch` | none | W3 |
| *`baas run` can report its outcome as a machine-readable object* (cli-command-structure, MODIFIED) | `printJobSummary` (l.944), `recordEndStatus` (l.290) | `aDetachedLaunchIsReportedAsLaunchedNotCompleted`, `anUnrecordedDetachedLaunchReportsLaunching`, `aDetachedLaunchThatFailedIsReportedAsFailed`, `anAttachedSuccessIsStillReportedAsCompleted`, `theSummaryKeepsTheDetachedStatusRatherThanTheSessionsEmptyEnd`; `RunCommandSummaryTest` (unchanged scenarios); L1, L5 | — |
| *The self-test covers the image-dependent benchmark type…* (ci-benchmark-execution, MODIFIED) | `e2e-cloud-test.yml` launch and follow-up steps | CI run 38257367829 | W4 |

Hints (design, *The detached hint points at `baas jobs show`*): `hints` (l.304); `aDetachedJobIsFollowedNotQueried`, `anAttachedSuccessStillPointsAtItsMeasurements`.

### Warnings (W1 above)

- **W2:** *A detached job that hangs is ended by its watchdog* was not run in this change. L4 exercised
  layer 2 (`timeout` → `failed:124`), not the watchdog. The watchdog path with no CLI is what
  `run-status-in-dynamodb`'s W1 verified live (the CLI `kill -9`ed after launch → `timed-out`,
  instance terminated), and this change touches neither user-data nor the watchdog, so a detached
  job reaches it identically. Recommendation: none needed unless user-data changes; a live
  `--timeout` above a benchmark that ignores interruption would re-prove it at ~6 minutes' cost.
- **W3:** *A failed detached launch* has no test of its own. `RunInstances` failing returns 1 through
  `recordLaunchFailure` before `afterLaunch` is reached, the same lines as an attached run, which
  PR #83's live J8 exercised. Recommendation: accept; a test would need `execute()`'s AWS clients
  behind a seam.
- **W4:** *A detached self-test cannot hang CI* — the bound (`deadline`, `e2e-cloud-test.yml` l.164,
  l.180) has not fired in a real run; run 38255555596 hung for the different reason recorded as W1,
  which the `.job.status` guard now fails within one poll. Recommendation: accept; forcing it would
  take a 2-hour CI job.

### Assessment

No critical issue. 20/20 tasks done; every requirement implemented; W2–W4 are coverage notes on
unchanged paths, and W1 was fixed and re-proven in CI. Ready to archive once PR #85 merges into
`next-release`.

## Deviations from design and tasks

- **CI names its timeout and margin** (task 3.1, design *CI's `jmh-with-async` job goes detached*). The
  design said the follow-up bound would be read from the deployment defaults; `baas config show` has
  no machine-readable output to read them from, so the `benchmark` job passes
  `--timeout 7200 --watchdog-margin 300` — the defaults it already ran with — and bounds its loop by
  the same two values. design.md was updated to match.

## 5.2 and 5.3 — not applicable

- **Measurement comparison (5.2):** nothing reaches the instance, the benchmark process, user-data or
  `environment.json`; `--detach` only changes when the launching CLI exits. A detached job measures
  exactly what an attached one does, so no comparison was run.
- **Second deployment (5.3):** no change to `infra/`, IAM or the runner image. The CLI's new path
  uses only writes `OperatorRole` already holds.

## Warnings

- **W1 — the first CI follow-up loop read `.status`, which `jobs show` does not have at the top level**
  (found 2026-10-12 on PR #85's first E2E run, run 38255555596). Task 1.2 recorded the wrong shape, so
  the loop read `null` forever: the job itself `completed` (`20261012T132551853Z-ce92709a`, instance
  terminated) while the CI step kept polling toward its 7515 s bound. Fixed: the loop reads
  `.job.status` and fails at once when a successful lookup carries none. Verified by running the
  corrected loop body against that completed job (ends `completed`). The detach itself was not at
  fault.
