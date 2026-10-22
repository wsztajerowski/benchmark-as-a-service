# Alive findings after the slice-architecture refactor

> **Superseded 2026-10-22.** Every finding still open was re-checked against `next-release` and
> moved into [`docs/review/open-findings.md`](../open-findings.md), which is now the list to work
> from. This file stays as the record of the triage and its decisions.

Every finding from the 2026-10-13 codebase review that is **still open** on `slice-architecture`
(code final at `72df695`), re-checked finding by finding against the refactored code on 2026-10-15
and 2026-10-19. This is the list to triage together. Each ID links to its report, where the
evidence, the failure scenario and the recommendation live with current file:line citations.

| Review | Open | Fixed by the refactor | Notes |
|---|---|---|---|
| [security.md](security.md) (X) | 8 | 0 | The refactor did not touch IAM, the templates or the installer |
| [api-gaps.md](api-gaps.md) (G) | 20 | 1 (G16) | G17 half fixed: every JSON string is escaped now; the missing fields remain |
| [code-complexity.md](code-complexity.md) (K) | 10 | 3 (K1, K7, K8) | K10 was fixed earlier by interactive-setup |
| [test-complexity.md](test-complexity.md) (T) | 12 | 1 (T1) | T4 half fixed: three of four in-memory recorders are bound to the contract |
| [architecture.md](architecture.md) (B) | 4 | 12 (B1–B5, B8–B10, B12, B14, B15) | B15 was found and fixed in the same branch |
| [implementation-review.md](implementation-review.md) (I) | 0 | 14 | The independent review of the refactor itself |
| [simplification-review.md](simplification-review.md) (Z) | 2 decisions | 8 applied | Z9, Z12 are decisions, not refactors |

The user-facing surface did not change, apart from three deliberate details recorded in ADR 0009:
JSON payloads escape every string field, `run`'s diagnostics carry `JobLaunch` as their logger name,
and `jobs diff` tolerates a trailing slash on a result path. Help output (24 commands), 28 offline
behaviours and the rendered user-data script were compared byte for byte against the pre-refactor
CLI and are identical.

## Triage order

Ordered by necessity, then by how much each fix unlocks. Effort uses the scale in
`docs/review/open-findings.md` (XS < 1 h, S a few hours, M an OpenSpec change).

### Decided in triage

| ID | Decision (2026-10-20) |
|---|---|
| G2 | **Status and exit code stay as they are.** A JCStress job that ran and stored its results is `completed`, and `baas run` exits 0, whatever its tests found: the status says the run finished, not what it measured. The verdict is shown instead. The score cell reads `passed/total` with a mark when tests failed. JSON gains `jcstress {total, passed, failed, errors, failedTests[]}`, and secondary metrics go on `-v` lines. Downgraded from Must to Should: it is now a display change only. |
| X1 | **Accepted risk** (2026-10-21), recorded in CLAUDE.md's *Accepted risks*. A per-launch check against a runner digest built into the CLI was implemented (a5b7543) and reverted (195b4c6) as not worth its build step. |
| G6 | **`--format json` and `--format csv` have no default limit** in `results query` and `jobs list`. The table keeps 20, and an explicit `--limit`/`--offset` applies to every format. The JSON stays a bare array: no envelope. User-facing: a pipe now gets every row. |
| X4 | **Accepted risk** (2026-10-22), recorded in CLAUDE.md's *Accepted risks*; CLAUDE.md's two "prefix-exact" claims now name the exception. The Image Builder wildcard reaches only a deployment whose name extends this one's, and only its Image Builder resources; the deployer is already effectively account admin. |
| G13 | **Deferred** — to be addressed later. |
| K2 | **Done the plain way.** Validators throw `CommandRefusal(exitCode, message)`, logged once by `BaasApp.reportFailure` without the `-v` hint. Exit codes and message text are unchanged; the refusal line's short logger name becomes `BaasApp`, recorded in ADR 0009. `JobLaunch`'s reservation failure keeps logging in place, so it still prints before `run`'s hint. |
| G5 | **Deferred** — skipped in triage. |
| G3 | **To discuss with users** before building: the proposal is in [user-discussion.md](user-discussion.md). |
| G4 | **To discuss with users**, as G3. |
| G8 | **To discuss with users**, as part of a larger design of what BaaS records about a result. |
| G10 | **Deferred** — skipped in triage. |
| Z9 | **Drop the fallback.** `JobPaths` becomes `recorder.find(jobId).map(JobItem::resultPath)`; `ResultsQueryService.resultPathFromMeasurements` and its IT cases go. Checked read-only on 2026-10-20: neither `baas-381492019823` (16 measurements, 16 jobs) nor `baas-development` holds a measurement whose job has no job item. |
| Z12 | **Already decided** (2026-10-04) and queued as `retire-mongodb`, QUEUE item 3. Nothing to triage. |
| Could items | **Left as "when nearby"**: fixed when work touches their code, not scheduled. |

### Fixed after triage (2026-10-20/21)

| ID | Fix | Commit |
|---|---|---|
| K6 | The async-profiler service skips a missing per-benchmark directory, like `-prof`, through one `ProfilerArtifacts.upload` | `ecaf261` |
| T13 | The setup terminal test writes its policy under its temp root; the stray `baas-cli/policy.json` is deleted | `0abe97f` |
| G1 | `baas run` refuses, exit 2, a runner option it sets itself (`RunnerArgs.SET_BY_THE_CLI`) among the arguments after `--` | `7bceb85` |
| G6 | `json`/`csv` have no default limit | `5b21c7e` |
| Z9 | `JobPaths` reads the job item only | `f1c4ea0` |
| X2 | `runner-image.yaml` pins async-profiler's SHA-256, checked before unpacking; `imageVersion` 1.3.1 | `f331311` |
| G2 | JCStress `passed/total ✗` in the score, `jcstress` and `secondaryMetrics` in JSON, `failed`/`metrics` lines under `-v` | `9bd7791` |
| K2 | `CommandRefusal` through `BaasApp.reportFailure`, installed by `BaasApp.commandLine` for `main` and tests alike | `d8e7691` |
| T4 | `RunCommandDetachTest` uses the contract-bound fakes | `3ee103c` |
| T3 | `JmhOptionForwardingTest`; `JCStressHtmlResultParserTest` over a real `index.html` fixture | `b6b8d62` |
| K5 | `UserDataScriptBuilder.build(region, DeploymentNames, JobItem, RunnerImage, Request, runnerJarKey)` | `c498a9b` |
| T2, B6 | `TeardownAws` and `ImageBuildAws` ports with recording fakes and one ordering test per rule; each test was checked against a deliberately reordered build | `467b118`, `2254e72` |
| K13 | `SetupAws.StackState` replaces the SDK's `StackStatus` at the port (a Could, done with B6) | `9e8b8f6` |
| B7 | Setup's name and region reach the persisted config only in `DoneStep.save`; sync's dead region restore is gone | `d43ded5` |

### Still open from the Should list

| ID | Finding | Effort | Status |
|---|---|---|---|
| G13 | No way to forget a deployment locally or reset a preference | XS | Deferred in triage |
| G5 | No `jobs wait` (or `terminal` field) for CI | S | Deferred in triage |
| G10 | The runner cannot pass JMH `-p` overrides or more than one include regex | S | Deferred in triage |
| G3 | No way to exclude, re-tag or delete a measurement after the run | M | In [user-discussion.md](user-discussion.md) |
| G4 | No score comparison between jobs or against a baseline | M | In [user-discussion.md](user-discussion.md) |
| G8 | The job record does not say what ran (arguments, runner version, timeout, mode, JAR digest) | S | In [user-discussion.md](user-discussion.md) |
| B6 (rest) | The thin `jobs`/`results`/`config` read commands still build their clients inline | — | Left by design: ADR 0009 adds a port when a sequence test needs one |

### Could — when nearby

| Area | IDs |
|---|---|
| Security hardening | X3 (runner can create job items / malformed rows), X5 (OIDC inputs unvalidated), X6 (one session name for every operator), X7 (runner-written text reaches the terminal raw; `jobs show --format json` splices `environment.json`), X8 (checksums from the same release as the assets) |
| API surface | G7 (exit codes), G9 (PARAMS validated only on the instance), G11 (`jobs diff` has no JSON, exits 0), G12 (more commands without machine-readable output), G14 (no read-only view of server-side state), G15 (no prune), G17 (missing JSON fields), G18 (no non-interactive project list), G19 (stale contract docs), G20 (no pointer to image-build logs), G21 (no idempotency key for `run`) |
| Code | K3 (job object written twice), K4 (`--format` checked by hand ×5), K9 (`session()`/`console()` getters ×8), K11 (derivable `exitCode`), K12 (mutable static `@Spec`), K14 (AccessDenied hand-over written twice) |
| Tests | T5 (no shared command-test harness), T6 (positional fixtures rebuilt per class), T7 (sleep-then-assert), T8 (pinned call count), T9 (global state without `@ResourceLock`), T10 (isolation only in surefire config), T11 (LocalStack pin ×6), T12 (Docker-less verify skips the stores) |
| Architecture | B11 (upload inside `JmhRunResults`; `@Entity` on parse DTOs — goes with `retire-mongodb`), B13 (EC2 state strings in the job domain) |
