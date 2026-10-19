# Alive findings after the slice-architecture refactor

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

### Must

| ID | Finding | Effort | Note |
|---|---|---|---|
| G2 | A JCStress job whose tests fail ends `completed`, exit 0; no command shows the pass/fail counts or JMH secondary metrics | S | Needs a decision first: was hiding the verdict intended (`jcstress-e2e` deliberately does not assert pass/fail)? |

### Should — fix soon

| ID | Finding | Effort | Note |
|---|---|---|---|
| K6 | The async-profiler service has no missing-directory guard: a benchmark with no profiler output fails the job after its measurement | XS | A real bug; the `-prof` service has the guard. One `if` and a test |
| T13 | A setup test writes `baas-cli/policy.json`, and the file is tracked on `next-release` | XS | Point the test at a `@TempDir`, delete the file |
| X1 | Any operator (so any CI ref) can overwrite `releases/<v>/benchmark-runner.jar`; nothing re-checks it once seeded | S | Deny writes to `releases/*` for the operator, or re-verify the checksum per launch — a decision |
| X2 | async-profiler is baked into the image from a tarball with no checksum | S | Pin a SHA-256 in `runner-image.yaml` |
| G1 | Text after `--` overrides runner options the CLI guards (`--tag imageVersion=`, `--tag project=`, `--project`) | XS | `RunnerArgs` now names the options to scan for |
| G6 | `--format json/csv` is silently cut to 20 rows | XS | |
| G13 | No way to forget a deployment locally or reset a preference | XS | |
| T2 | Teardown and image build sequencing is untested (adapters built inline) | S | Give them a port in the `RunAws`/`SetupAws` shape |
| T3 | Runner commands layer: JMH option forwarding and the JCStress HTML parser have no unit test | S | |
| T4 | `RunCommandDetachTest`'s recorder is not bound to `JobRecorderContract` and breaks three of its rules | XS | Bind it, or use `FakeRunAws`'s recorder |
| K2 | 52 hand-rolled "log, return 1/2" exits | S | Was left out of the refactor on purpose: routing refusals through one handler changes their stderr prefix |
| K5 | `UserDataScriptBuilder.build` takes 16 positional parameters | S | Its only caller, `JobLaunch`, already holds the three objects they come from |
| G3 | No way to exclude, re-tag or delete a measurement after the run | M | |
| G4 | No score comparison between jobs or against a baseline | M | |
| G5 | No `jobs wait` (or `terminal` field) for CI | S | |
| G8 | The job record does not say what ran (arguments, runner version, timeout, mode, JAR digest) | S | |
| G10 | The runner cannot pass JMH `-p` overrides or more than one include regex | S | |
| B6 | Nine command classes still build AWS clients inline; six anonymous-subclass tests remain | M | Partly fixed: `DeployerSession`, and `run` has a port |
| B7 | Only `JobLaunch` takes the immutable `DeploymentNames`; everything else holds the mutable `BaasConfig` | S | Partly fixed |

### Could — when nearby

| Area | IDs |
|---|---|
| Security hardening | X3 (runner can create job items / malformed rows), X4 (deployer Image Builder resource is prefix-wide — the policy budget now fits the fix), X5 (OIDC inputs unvalidated), X6 (one session name for every operator), X7 (runner-written text reaches the terminal raw; `jobs show --format json` splices `environment.json`), X8 (checksums from the same release as the assets) |
| API surface | G7 (exit codes), G9 (PARAMS validated only on the instance), G11 (`jobs diff` has no JSON, exits 0), G12 (more commands without machine-readable output), G14 (no read-only view of server-side state), G15 (no prune), G17 (missing JSON fields), G18 (no non-interactive project list), G19 (stale contract docs), G20 (no pointer to image-build logs), G21 (no idempotency key for `run`) |
| Code | K3 (job object written twice), K4 (`--format` checked by hand ×5), K9 (`session()`/`console()` getters ×8), K11 (derivable `exitCode`), K12 (mutable static `@Spec`), K13 (`SetupAws` returns the SDK's `StackStatus`), K14 (AccessDenied hand-over written twice) |
| Tests | T5 (no shared command-test harness), T6 (positional fixtures rebuilt per class), T7 (sleep-then-assert), T8 (pinned call count), T9 (global state without `@ResourceLock`), T10 (isolation only in surefire config), T11 (LocalStack pin ×6), T12 (Docker-less verify skips the stores) |
| Architecture | B11 (upload inside `JmhRunResults`; `@Entity` on parse DTOs — goes with `retire-mongodb`), B13 (EC2 state strings in the job domain) |

### Decisions, not refactors

| ID | Question |
|---|---|
| Z9 | Drop `JobPaths`' fallback to measurements for jobs recorded before job items? Simpler, but such a job would answer "No job found" |
| Z12 | Retire MongoDB from the runner — already queued as `retire-mongodb` |
