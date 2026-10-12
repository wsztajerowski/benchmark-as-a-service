# Design

## Context

See proposal.md for why. The exploration (`exploration.md`, 2026-10-05) predates the run→job rename,
`jobs-command` and the PR #83 fixes; this design rests on its 2026-10-12 re-check against
`next-release` `6570817`, and where the two disagree, this file wins.

What `baas run` does today (`RunCommand.execute`):

```
reserve ─► session set ─► hook registered ─► RunInstances ─► confirmLaunched ─► poll (await) ─► report
           (l.455)        (l.462)                            (l.497)            cap = watchdog bound
```

- The hook calls `session.stop(CANCELLED)` whenever `!session.ended()`. A confirmed launch does not
  end the session, so any exit between `confirmLaunched` and the end of the poll cancels the job and
  terminates its instance (DR1). It fires on every JVM exit, not only on Ctrl+C.
- `confirmLaunched` returns `CONFIRMED` (the `launched` write landed, or was refused because the
  instance already recorded `running` or an outcome after which it ends itself), `UNCONFIRMED` (the
  write failed; the instance records itself once it boots), or `CANCELLED_WHILE_LAUNCHING` (the job
  was stopped during `RunInstances`; already terminated, exit 1).
- The JSON summary is printed in `call()`'s `finally`. `jobStatus` is copied from
  `session.endStatus()`, which is `null` until the job has ended; `status` is
  `exitCode == 0 ? "completed" : "failed"`.
- The follow-up side is complete: `baas jobs show <id> --format json` carries `status` (resolved
  through `JobListing.resolve`, so never `vanished` inside the 5-minute `VANISH_GRACE`) and
  `storedStatus`; `baas jobs terminate` stops any job of the deployment through `JobStop`.
- The watchdog alone ending a CLI-less job was verified live (W1 of `run-status-in-dynamodb`, ADR 0003):
  with the CLI `kill -9`ed after launch, it recorded `timed-out`, uploaded the boot log and terminated.

## Goals / Non-Goals

**Goals:**
- A detached launch is as safe as an attached one up to the confirmed launch, and touches nothing
  after it.
- The attached path is unchanged — same code path, same output, same hook behaviour.
- CI keeps covering both the detached path and the attached poll.

**Non-Goals:**
- Re-attaching to a running job, or a `jobs wait` command (exploration §9: additive later).
- Recording the launch mode anywhere (exploration §10).
- Notifying anyone that a detached job failed (exploration §8).

## Decisions

### The hook stays armed through the launch and is disarmed by a flag it checks

`RunCommand` gains a `volatile boolean detached`, set immediately after `confirmLaunched` returns
`CONFIRMED` or `UNCONFIRMED` under `--detach`. The hook returns without calling `stop` when it is
set. Agreed with the user 2026-10-12.

- **Rejected: `Runtime.removeShutdownHook`.** It throws `IllegalStateException` once the JVM is
  already shutting down, needs the `Thread` kept in a field, and cannot be exercised by a unit test
  without registering real hooks. A flag is one read in the hook and testable by invoking the hook's
  body directly.
- **Rejected: registering the hook only for attached runs.** The launch window is exactly where the
  hook earns its keep for a detached run too: an interrupt during `RunInstances` must still cancel.
- **Rejected: ending the session (`ended = true`) to silence the hook.** `ended` means "the job
  reached an outcome"; a detached job has not, and `endStatus` would then claim one.

The window between `confirmLaunched` returning and the flag being set is a few instructions long. A
Ctrl+C that lands in it cancels the job — which is what Ctrl+C during a launch means, so it is not a
defect to close.

### A detached launch returns from `execute()` before `poll`, through the existing exit path

After `confirmLaunched`, `--detach` sets the flag, records the summary's `jobStatus` and returns 0.
`CANCELLED_WHILE_LAUNCHING` keeps returning 1 before the branch, and a failed `RunInstances` keeps
returning 1 through `recordLaunchFailure`, so both failure paths are shared with the attached run.
`call()`'s `finally` prints the summary and the hint as today.

- **Rejected: a separate `DetachedRun` path or a `poll` with cap 0.** A cap of 0 would go through
  `await`'s cap branch and *write* `timed-out`. A second path would duplicate the reservation and
  launch logic that DR1 is about keeping identical.

### `jobStatus` for a detached launch is what the CLI knows the item says

`launched` when `confirmLaunched` returned `CONFIRMED`; `launching` when `UNCONFIRMED`. The
summary's `status` is `launched` for a detached exit 0 (Q4, 2026-10-05): a new value seen only by
callers who passed `--detach`, so no existing consumer meets it.

A `CONFIRMED` that was in fact a refusal over `running` or `completed` (a slow `RunInstances` answer)
still reports `launched`. Re-reading the item to report `running` would add a call for a snapshot
that is stale a second later either way; `jobs show` is the place for the current status.

- **Rejected: `status` = `completed` with consumers reading `jobStatus`.** It claims a job finished
  that has not started; the spec forbids it.

### The detached hint points at `baas jobs show`

On a detached exit 0 the text output says the job is detached and how to follow it, and `Hints` shows
`baas jobs show <jobId>` and `baas jobs terminate <jobId>` instead of the measurements and artifacts
of a job that has not run.

### For a detached job, `baas jobs terminate` is the third termination layer

Recorded in **ADR 0007**, which also supersedes the archived `run-status-in-dynamodb` non-goal
(its stated reason, run discovery by tag, was removed by job items) and amends ADR 0001's table row.
CLAUDE.md's *Three termination layers* gets one sentence under layer 3. The watchdog and the process
`timeout` are untouched, and they are what bound a detached job's cost.

### CI's `jmh-with-async` job goes detached; the JCStress job stays attached

The `benchmark` job runs `baas run --detach --format json`, asserts `status == launched`, then polls
`baas jobs show <id> --format json` every 15 s until `status` is terminal or `vanished`, bounded by
the job's watchdog bound plus one poll. The CI job names its timeout and margin explicitly
(`--timeout 7200 --watchdog-margin 300`, the deployment defaults it ran with before), because
`baas config show` has no machine-readable output to read them from. It then fails unless the status is `completed`; every later assertion runs
unchanged. The JCStress job keeps the attached poll covered (Q6, 2026-10-05).

`cancel-in-progress` stays `false`. The detached CI job still polls inside the same GitHub job, so the
per-ref concurrency group still holds its slot: this corrects the exploration's Q5 note, which assumed
the slot was freed a minute after launch. Cancelling the CI job mid-poll no longer kills a layer —
the job runs on — but there is nothing to gain from changing the setting in this change.

## Risks / Trade-offs

- [A detached job orphaned by a broken watchdog has no CLI behind it] → The watchdog and `timeout` are
  the same layers an attached job relies on when its laptop sleeps; W1 verified the CLI-less path
  live. The CI detached job exercises it on every triggering push.
- [Someone runs `--detach` and forgets the job] → It ends itself within timeout + margin, exactly as
  bounded as an attached job whose CLI was killed. `baas jobs list --in-flight` shows it.
- [A CI poll loop that never ends] → Bounded by the watchdog bound; past it the step fails and the
  boot-log step runs.
- [The `UNCONFIRMED` case reports `launching` for a job that is in fact running] → Accurate to the
  item at that moment; `jobs show` resolves it a poll later.
- [Comparability] → None: nothing reaches the instance or `environment.json`.

## Migration Plan

Additive. Ships in the next `next-release` batch; no deployment update, no `baas admin deployment
setup`, no image build. Rollback is reverting the commits; jobs launched detached by the new CLI
finish on their own either way.

## Open Questions

None.

## Resolved Questions

- **How the hook is disarmed** — a flag the hook checks (2026-10-12, user).
- **The summary's `status` for a detach** — `launched` (Q4, 2026-10-05).
- **Which CI job goes detached** — `jmh-with-async`; JCStress stays attached (Q6, 2026-10-05).
- **A waiter** — none (§9, 2026-10-05).
- **Is DR3 (a just-launched job read as `vanished`) still open?** — No: `jobs show` resolves through
  `JobListing.resolve`, which keeps the stored status for `VANISH_GRACE` (5 min) after creation
  (re-check 2026-10-12).
