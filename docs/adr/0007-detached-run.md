# ADR 0007 — A detached job's third termination layer is `baas jobs terminate`

- **Status:** Accepted — implemented in `detached-run`
- **Date accepted:** 2026-10-12
- **Supersedes:** the `run-status-in-dynamodb` non-goal "Reattaching a new `baas run` process to a
  detached run (`--detach`/`attach`, rejected in the usage analysis §5)"
  (`openspec/changes/archive/2026-10-04-run-status-in-dynamodb/design.md`); amends ADR 0001's
  *Three independent termination layers* row
- **Closes findings:** DR1, DR4

## Context

`baas run` waited for the whole job, so a CI job that launched a benchmark held a GitHub Actions runner
for up to the 7200 s default timeout, only to read a status the job item already recorded. Three
termination layers guard a paid instance: the process `timeout`, the shell watchdog at timeout +
margin, and the CLI's shutdown hook, which records `cancelled` and terminates when the CLI stops while
the job is in flight.

That hook fires on every JVM exit, not only on Ctrl+C, and a confirmed launch does not end the job.
A `baas run` that simply returned after the launch would therefore cancel and terminate the job it had
just launched (DR1).

The 2026-10-04 non-goal rejected `--detach` because a detached run could only be found again by
discovering its instance by tag. Job items removed that reason: every job is one item, found by id
through `baas jobs show`, which resolves its status and never reports a just-launched job as
`vanished` within the five-minute grace (DR4).

## Decision

`baas run --detach` reserves, launches and confirms exactly as an attached run, then returns. The
shutdown hook stays armed through `RunInstances` and the launch confirmation, so an interrupt in that
window cancels a detached job as it does an attached one. Once the launch is confirmed, a flag the
hook checks disarms it, and the CLI exits without touching the job.

For a detached job the three layers are:

1. The shell watchdog — unchanged.
2. The process `timeout` — unchanged.
3. **`baas jobs terminate <jobId>`**, run by any operator, in place of the launching CLI's hook.

The job item does not record the launch mode: status is lifecycle, and the instance's `running`
write would replace a `detached` status a minute after launch.

## Consequences

- A detached job is bounded exactly as an attached job whose CLI was killed: the watchdog ends it at
  timeout + margin. That path was verified live (`run-status-in-dynamodb` W1, ADR 0003).
- The flag is set a few instructions after the launch confirmation returns. A Ctrl+C in that window
  cancels the job — which is what an interrupt during a launch means — so it is not closed.
- The flag rather than `Runtime.removeShutdownHook`: removal throws once the JVM is shutting down,
  and a flag is unit-testable by calling the hook's body.
- A consumer that fires and forgets loses the bound the per-ref CI concurrency group gave: each push
  runs its own instance, each ended by its watchdog (accepted 2026-10-05). This repository's own CI
  still follows its detached job in the same GitHub job, so the bound holds here.
- Attached `baas run` is unchanged, layers and output alike.
