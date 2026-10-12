# Proposal

## Why

`baas run` holds its caller for the whole benchmark: a continuous-integration job that launches one
keeps a GitHub Actions runner busy for up to the 7200 s default timeout, only to read a status the
job item already records. Everything a caller needs to follow a job without the launching process now
exists — the job item, `baas jobs show <id> --format json` with a resolved status, the vanish grace
period, and a watchdog verified to end a job whose CLI is gone — so the remaining obstacle is the CLI
itself: its shutdown hook would cancel a job it returned from (DR1).

Exploration: [`exploration.md`](exploration.md) (2026-10-05), re-checked against `next-release`
`6570817` on 2026-10-12.

## What Changes

- `baas run` (and `baas jobs run`) gains `--detach`: reserve, launch and confirm exactly as today,
  then return without polling. The job runs to its end on the instance alone.
- The shutdown hook stays armed through `RunInstances` and the launch confirmation, so Ctrl+C during
  a detached launch still cancels and terminates as it does attached; once the launch is confirmed
  it is disarmed, and the CLI exits without touching the job.
- A detached `--format json` summary reports `"status":"launched"` with `jobStatus` set to what the
  item says at that moment (`launched`, or `launching` when the launch write was lost), exit 0. A
  launch that fails or is cancelled while launching reports `failed` and exits non-zero, as today.
- The text output and the next-step hint of a detached job point at `baas jobs show <jobId>`, not at
  measurements that do not exist yet.
- For a detached job the third termination layer is `baas jobs terminate`; the watchdog and the
  process `timeout` are unchanged. Recorded in a new ADR that also supersedes the archived
  `run-status-in-dynamodb` non-goal rejecting `--detach` (DR4).
- The CI self-test's `jmh-with-async` job launches detached and follows the job with a bounded
  `baas jobs show` loop; the JCStress job stays attached, so both paths stay covered.
- Not added: a `jobs wait` command, a `detached` item field or status, a superseding policy for PR
  jobs, and the failure-notification recipe (exploration §8, written once detach works).

No breaking change: without `--detach`, `baas run` is byte-for-byte what it is today.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `job-tracking`: a detached launch — what it records, what it leaves to the instance, and that
  nothing in the CLI stops a job after a confirmed detached launch.
- `cli-command-structure`: the `--format json` summary of a detached launch.
- `ci-benchmark-execution`: the image-dependent self-test launches detached and follows the job to
  its end.

## Impact

- **Code:** `baas-cli` only — `RunCommand` (option, early return, hook flag, summary, hints). No change
  to `baas-model`, `benchmark-runner`, user-data, the image or `infra/`.
- **CI:** `.github/workflows/e2e-cloud-test.yml`, the `benchmark` job.
- **Docs:** ADR 0007; CLAUDE.md's *Three termination layers*; `docs/diagrams/baas-run.mmd` and
  `baas-ci-e2e.mmd`; `docs/review/open-findings.md` (DR1, DR4 removed).
- **Cost:** no AWS resource changes. A detached job costs what an attached one does; in this
  repository's CI the job still polls, so the per-ref concurrency group keeps bounding instances. A
  consumer that truly fires and forgets loses that bound — each push then runs its own instance,
  each ended by its watchdog (accepted 2026-10-05, Q5).
- **IAM:** none. Every write a detached job involves is already granted.
- **Comparability:** none. Nothing reaches the instance, the benchmark process or `environment.json`;
  a detached job measures exactly what an attached one does.
- **Deliberately unchanged:** all three termination layers for an attached job; the watchdog bound
  and `ShutdownBehavior=TERMINATE`; the job-status vocabulary and the first-terminal-status-wins rule;
  `cancel-in-progress: false` in CI.

Closes **DR1** and **DR4** (`docs/review/open-findings.md`).
