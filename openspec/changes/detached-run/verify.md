# Verification — detached-run

## 1. Blocking assumptions (2026-10-12, `detached-run` at `2a72237`)

| Task | Finding |
|---|---|
| 1.1 | `BaasApp.main` ends in `System.exit(commandLine(app)….execute(args))`, and `System.exit` runs every registered shutdown hook. The hook (`RunCommand` l.462) calls `stop(CANCELLED)` whenever `!session.ended()`, and a confirmed launch does not end the session. So a detached return without the flag cancels and terminates the job it launched: DR1 holds exactly as design.md *Context* states it. |
| 1.2 | `baas jobs show <id> --format json` exits 0 for any job it finds (1 for none, 2 for a malformed id). Its object carries `status` — `JobListing.resolve`'s, so never `vanished` within `VANISH_GRACE` of creation — and `storedStatus`. The CI loop stops when `status` is terminal by `JobStatus.isTerminal` (`completed`, `timed-out`, `cancelled`, `launch-failed`, `failed:<n>`) or is `vanished`. |
| 1.3 | ADR 0007 is free (0001–0006 exist). The poll is drawn in `baas-run.mmd` and `baas-ci-e2e.mmd`. `baas-states-job.mmd` gains no state or transition, but its header comments name the hook (H) and what `--format json` reports, so they gain one comment line each (no rendered change). The C4 views (`c4-2-container`, `c4-3-component-cli`, `c4-4-deployment`) say "launch / poll" in general terms and stay accurate. |

## 2.6 — full reactor build (2026-10-12)

`mvn verify` with `ASYNC_PATH` exported: **BUILD SUCCESS, 959 tests, 0 failures, 0 errors, 0 skipped**
(runner 73 unit + 44 IT, `baas-model` 18, `baas-cli` 784 unit + 40 IT).
`JmhWithAsyncProfilerSubcommandServiceIT` ran (1 test, not skipped); `RunCommandDetachTest` ran 15.
`java -jar baas-cli/target/baas-cli.jar run --help` lists `--detach` with its help text.

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
