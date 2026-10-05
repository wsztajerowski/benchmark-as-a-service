# ADR 0003 — An outcome the instance recorded is never overridden by the CLI

- **Status:** Accepted — implemented and verified live (PR #77, 2026-10-05)
- **Date accepted:** 2026-10-04
- **Builds on:** `openspec/changes/archive/2026-10-04-run-status-in-dynamodb/` (the run item, its
  writers and conditional updates — that design is not repeated here)
- **Closes findings:** U27, U30, U38; and that change's open warning W1 (watchdog never fired live)

## Context

A run's status lives on its run item. The CLI writes `launching`, `launched`, `cancelled`,
`timed-out` and `launch-failed`; the instance writes `running`, `completed` and `failed:<n>`, and
the watchdog `timed-out`. Every write after the reservation refuses to replace a terminal status,
so the first outcome to land wins. That part held. What did not hold was what the CLI *did* after
one of its writes was refused:

- **U38**, observed live: on a slow link `RunInstances` answered about five minutes late, after the
  instance had booted, run the benchmark and recorded `completed`. The CLI's late `launched` write
  was refused — correctly — but `confirmLaunched` read *any* terminal status as "stopped while
  launching", terminated the self-terminating instance and exited 1 over a good run.
- **U30**: `baas runs terminate` on an item at `completed` whose instance was still live terminated
  it, cutting off the boot-log upload that is the only record of a failed run.
- **U27**: `baas run --format json` reported only `completed`/`failed`, so a consumer could not tell a
  timeout from a cancellation without listing runs and filtering by id.

`RunSession.stop` already followed the right rule; the other two paths did not.

## Decision

1. **`completed` and `failed:<n>` are written only by the instance, and mean it got there first.**
   After them it uploads its boot log and terminates itself. No CLI path terminates it or reports
   the run as anything but that outcome. One predicate states it — `RunStatus.isRecordedByInstance` —
   and `RunSession.stop`, `RunSession.confirmLaunched` and `RunTermination` all use it.
2. **The CLI's own terminal statuses keep their meaning.** A refusal over `cancelled`, `timed-out` or
   `launch-failed` written by this or another CLI still terminates the instance it just launched.
3. **The run summary carries the stored status** as `runStatus` beside the unchanged `status`/
   `exitCode`: a terminal status, `vanished`/`status-lost` when the poll computed one, or `null`
   before a run was recorded. CI reads it instead of listing runs.

## Verification of the termination layers (2026-10-05)

W1 asked for "a fixture benchmark that survives SIGTERM". **That cannot fire the watchdog**: the
runner starts the benchmark as a child JVM, so `timeout`'s SIGTERM ends the runner JVM whatever the
benchmark does, and the run records `failed:124` (confirmed live — which also verified the process
timeout, layer 2, with the CLI detached). The watchdog exists for a runner JVM that will not exit,
so that is what was simulated: a throwaway `--runner-jar` whose `main` sleeps forever behind a
shutdown hook that never returns. With `--timeout 60 --watchdog-margin 60`:

- CLI killed with SIGKILL after launch → the watchdog recorded `timed-out`, uploaded
  `cloud-init-output.log` and terminated the instance, about two minutes after launch.
- CLI attached → its poll cap fired at 120 s, recorded `timed-out` (not `cancelled`) and terminated
  the instance; `runStatus` read `timed-out`.

Run ids and log lines are in that change's `verify.md`. Repeat the method, not the benchmark
fixture, when the termination layers change.

## Consequences

- A slow `RunInstances` response or a late operator no longer turns a good run red in CI.
- U30 and U38 remain unit-tested only: both windows are seconds long and cannot be produced on demand.
