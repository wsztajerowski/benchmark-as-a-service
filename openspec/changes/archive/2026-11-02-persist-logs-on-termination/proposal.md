# Proposal

## Why

A job that is stopped from outside — Ctrl+C on `baas run`, `baas jobs terminate`, the CLI's poll
cap — loses every log its instance had produced: the CLI calls `TerminateInstances` and nothing on
the instance uploads anything first. The benchmark timeout path loses the process output too,
because `timeout` kills the runner before it saves `jmh-output.txt`. Exactly the jobs a user most
wants to inspect are the ones that leave only `environment.json` behind.

The runner image also bakes async-profiler 4.0, six releases behind 4.6 (2026-10-19).

## What Changes

- The instance ships what it has produced — `cloud-init-output.log`, the per-type process output
  (`*-output.txt`) and every `*.log` below `/app` — to the job's prefix whenever the instance shuts
  down, however the shutdown was started. An OS shutdown hook, installed by user-data right after
  the watchdog starts, does this; EC2's default `TerminateInstances` performs a graceful OS
  shutdown, so the CLI's termination paths reach it without any CLI change.
- The script's own exits (watchdog, refused `running`, the normal end, including a benchmark that
  hit its `timeout`) ship the same set explicitly before terminating, instead of the boot log alone.
- async-profiler 4.0 → 4.6 in `infra/runner-image.yaml`; base `imageVersion` 1.3.0 → 1.4.0. CI's
  async integration test follows to 4.6.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `job-artifact-layout`: adds a requirement that a stopped job's logs reach its prefix.

## Impact

- `UserDataScriptBuilder` (user-data), `infra/runner-image.yaml`, `.github/workflows/ci-pr-build.yml`,
  CLAUDE.md.
- Cost: none new. Billing stops at `shutting-down`, which is when the hook runs. A few more
  `PutObject`s per job.
- Comparability: a 1.4.0 image's results carry `imageVersion=1.4.0`, so `--tag imageVersion=1.3.0`
  never mixes them with older ones; only profiler-mode jobs use the upgraded library. The shutdown
  hook runs after the benchmark has stopped and does not touch a measurement.
- Not changed: the three termination layers, `JobStop`, the stored statuses, IAM (`RunnerRole`
  already holds `PutObject` on `jobs/*`), user-data still installs nothing (a unit file is
  configuration, not a tool), no `set -e`, the watchdog still starts immediately after
  `INSTANCE_ID`.
- Closes no `docs/review/open-findings.md` entry.
